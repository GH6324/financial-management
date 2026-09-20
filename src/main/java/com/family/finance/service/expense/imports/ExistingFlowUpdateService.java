package com.family.finance.service.expense.imports;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.period.Period;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.ExpenseFlowMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.service.EntryService;
import com.family.finance.service.expense.ExpenseCategoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.22 FR-598 ~ FR-600 · 就地修正<b>已经入账</b>的那些笔。
 *
 * <h3>为什么需要</h3>
 *
 * <p>「已存在」= 这个交易号上次导过。v1.21 把这一桶做成<b>只读</b>,理由是「不要制造双份」——
 * 那个理由只挡住了「再导一次」,却顺手把另一件完全合理的事也挡掉了:
 * <b>上次可能就归错了分类、落错了账户</b>。用户要改只能去填报页在几百行里翻,
 * 或者整批撤销重导(那会把上次的所有手工调整一起丢掉)。</p>
 *
 * <p>这里做的<b>不是新增</b>,是 UPDATE 已有行 —— 所以不存在双份的风险。</p>
 *
 * <h3>改账户为什么要挪余额</h3>
 *
 * <p>如果那笔当初勾了「落到账户」({@code affects_balance = 1}),它已经从某个账户的
 * 期末余额里扣过了。只改 {@code account_id} 不挪钱的话,余额从此对不上,<b>而且不报错</b> ——
 * 这正是 v1.21 一路在防的那类静默错账。</p>
 *
 * <p>所以:旧账户<b>加回</b>、新账户<b>扣掉</b>,一个事务里做完,走
 * {@link EntryService#applyImportedExpense} 的既有路径(审计日志与镜头失效都挂在那)。</p>
 *
 * <h3>已关账的期为什么只能改分类</h3>
 *
 * <p>去重范围是<b>整个家庭</b>而不是当期,所以「已存在」的笔可能落在别的账期、
 * 甚至已关账的期。改账户会去动一个用户<b>已经核对过并封存</b>的
 * {@code period_snapshot.end_balance} —— 那是他自己填的数字,不该被一次导入的顺手操作改掉。</p>
 *
 * <p>分类不动钱,所以照常可改。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExistingFlowUpdateService {

    private final ExpenseFlowMapper flowMapper;
    private final AccountMapper accountMapper;
    private final PeriodMapper periodMapper;
    private final EntryService entryService;
    private final ExpenseCategoryService categoryService;

    public static class UpdateException extends RuntimeException {
        public UpdateException(String m) { super(m); }
    }

    /** 改了几笔、其中几笔挪了余额、几笔因为已关账被拒 */
    public record Result(int categoryChanged, int accountChanged, int refusedClosed) {
        public int total() { return categoryChanged + accountChanged; }
    }

    /** 一行修正请求:交易号 + 想改成什么(null = 这一维不改) */
    public record Edit(String txNo, Long categoryId, Long accountId) {}

    /**
     * @param edits 用户在「已存在」那一桶里改过的行
     * @return 实际改了什么 —— 用来给用户一句如实的回执
     */
    @Transactional
    public Result apply(long familyId, long memberId, List<Edit> edits) {
        if (edits == null || edits.isEmpty()) return new Result(0, 0, 0);

        List<String> txNos = edits.stream().map(Edit::txNo)
                .filter(t -> t != null && !t.isBlank()).distinct().toList();
        if (txNos.isEmpty()) return new Result(0, 0, 0);

        /* 一次查全 —— 几十上百行时逐条查会把确认页拖成 N+1 */
        Map<String, ExpenseFlowMapper.ExistingRow> byTx = new LinkedHashMap<>();
        for (var r : flowMapper.findExistingByTxNos(familyId, txNos)) byTx.putIfAbsent(r.txNo(), r);

        Map<Long, Account> acctCache = new LinkedHashMap<>();
        Map<Long, Period> periodCache = new LinkedHashMap<>();
        int catChanged = 0, acctChanged = 0, refused = 0;
        List<String> refusedNotes = new ArrayList<>();

        for (Edit e : edits) {
            if (e.txNo() == null || e.txNo().isBlank()) continue;
            var row = byTx.get(e.txNo());
            /* 查不到 = 这个交易号不属于这个家,或者已经被撤销了。
             * 【静默跳过】而不是抛 —— 用户可能在另一个标签页撤销了那一批,
             * 为此让整个提交失败没有道理。最后的回执会说实际改了几笔。 */
            if (row == null) continue;

            // ── 分类:不动钱,已关账也能改 ──
            if (e.categoryId() != null && !e.categoryId().equals(row.expenseCategoryId())) {
                if (!categoryService.isUsable(familyId, e.categoryId())) {
                    throw new UpdateException("选了一个不能用的分类 —— 刷新一下页面再试。");
                }
                flowMapper.updateCategory(familyId, row.id(), e.categoryId());
                catChanged++;
            }

            // ── 账户:要挪余额,已关账的拒 ──
            if (e.accountId() == null || e.accountId() == row.accountId()) continue;

            if (row.closed()) {
                /* FR-600 · 服务端硬拦。前端已经把这些行的账户下拉 disabled 了,
                 * 但 disabled 只防手滑 —— 构造一个请求就能绕过去。 */
                refused++;
                refusedNotes.add(e.txNo());
                continue;
            }

            Account to = acctCache.computeIfAbsent(e.accountId(),
                    id -> accountMapper.findById(familyId, id).orElse(null));
            if (to == null || to.getFamilyId() == null || to.getFamilyId() != familyId) {
                throw new UpdateException("选了一个不属于你家的账户 —— 刷新一下页面再试。");
            }
            Account from = acctCache.computeIfAbsent(row.accountId(),
                    id -> accountMapper.findById(familyId, id).orElse(null));
            Period period = periodCache.computeIfAbsent(row.periodId(),
                    id -> periodMapper.findById(familyId, id).orElse(null));
            if (period == null) continue;

            flowMapper.updateAccount(familyId, row.id(), e.accountId());

            /* 【只有当初落到账户的笔才挪钱】。
             * 没落到账户的笔(affects_balance = 0)只回答「花在哪」,它从来没碰过余额,
             * 改归属时也一分不该动 —— 动了就是凭空扣一笔。 */
            if (row.affectsBalance() && row.amount() != null && row.amount().signum() != 0) {
                /* applyImportedExpense 内部走 delta.negate():传正数 = 余额减少。
                 * 所以旧账户传【负的金额】(加回来)、新账户传【正的金额】(扣掉)。
                 *
                 * 负债账户不需要特殊照顾:它的余额存的是负数,而这里是同一个加减法,
                 * 方向天然对(与 v1.19.3 放开信用卡消费时的判断一致)。
                 *
                 * 金额本身可以是负数(v1.22 起退款冲正原样记)—— 那时两边的符号一起翻,
                 * 结果仍然是「从旧账户身上撤销、在新账户身上重做」。 */
                if (from != null) {
                    entryService.applyImportedExpense(familyId, memberId, period, from,
                            row.amount().negate(), "改账户 · 从这里移出");
                }
                entryService.applyImportedExpense(familyId, memberId, period, to,
                        row.amount(), "改账户 · 移到这里");
            }
            acctChanged++;
        }

        if (refused > 0) {
            log.info("已存在行改账户被拒(账期已关账)· family={} 笔数={}", familyId, refused);
        }
        log.info("已存在行就地修正 · family={} 改分类={} 改账户={} 拒={}",
                familyId, catChanged, acctChanged, refused);
        return new Result(catChanged, acctChanged, refused);
    }
}
