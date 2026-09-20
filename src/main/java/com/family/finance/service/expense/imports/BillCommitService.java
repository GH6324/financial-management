package com.family.finance.service.expense.imports;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.expense.ExpenseImportBatch;
import com.family.finance.domain.expense.ExpenseSource;
import com.family.finance.domain.flow.CashFlow;
import com.family.finance.domain.flow.CashFlowKind;
import com.family.finance.domain.period.Period;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.CashFlowMapper;
import com.family.finance.repository.ExpenseFlowMapper;
import com.family.finance.repository.ExpenseImportBatchMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.service.expense.ExpenseCategoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.21 · 把用户核对过的草稿落成真流水。
 *
 * <h3>落的是真 {@code cash_flow},不是另一张汇总表</h3>
 *
 * <p>代价是明确的:这些笔<b>会参与账户余额轧差</b>(FR-569)。所以导入页必须写清
 * 「顺序仍是先录收支、最后核对余额」—— 用户已经按月末实际余额校准过的话,
 * 导入之后余额那一栏会重新出现差额,那是<b>对的</b>,不是 bug。</p>
 *
 * <h3>整批一个事务</h3>
 *
 * <p>几百笔要么全进要么全不进。落一半的后果比不落更糟:用户看到「导入了 300 笔」
 * 但实际只有 137 笔,而账单总额对不上时他会以为是解析器算错了。</p>
 *
 * <h3>为什么不走 EntryService.recordExpense</h3>
 *
 * <p>那条路每笔都会:校验账期开着 → 查账户 → <b>改账户余额</b> → 写审计日志 →
 * 发透视缓存失效事件。300 笔就是 300 次余额更新和 300 条审计。
 * 这里改成<b>一次</b>余额调整 + <b>一条</b>审计 —— 批量导入本来就是一个动作,
 * 不该在审计日志里炸成 300 条把别的记录冲掉。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BillCommitService {

    /** 一批的上限。再多说明用户在导整年账单 —— 那该按月分开导,否则落错账期没法收拾。 */
    public static final int MAX_ROWS = 2000;

    private final CashFlowMapper cashFlowMapper;
    private final ExpenseImportBatchMapper batchMapper;
    private final ExpenseFlowMapper flowMapper;
    private final AccountMapper accountMapper;
    private final PeriodMapper periodMapper;
    private final ExpenseCategoryService categoryService;
    private final com.family.finance.service.EntryService entryService;

    public static class CommitException extends RuntimeException {
        public CommitException(String m) { super(m); }
    }

    /** 落库结果 —— 说给用户听的那几个数 */
    public record Result(long batchId, int rows, BigDecimal amount, int notIncluded, int skipped) {}

    /**
     * @param lines 用户核对之后的行(分类可能已被改过)· 只有 SPEND 与 NATURE 桶会落库
     */
    @Transactional
    public Result commit(long familyId, long memberId, long periodId, long fallbackAccountId,
                         ExpenseSource channel, List<BillCategoryResolver.Line> lines,
                         int notIncluded, int skipped, boolean affectsBalance) {
        return commit(familyId, memberId, periodId, fallbackAccountId, channel, lines,
                notIncluded, skipped, affectsBalance, java.util.Set.of());
    }

    /**
     * v1.24 FR-613 · 带「这笔是一次性的」的导入(<b>写入路 W2</b>)。
     *
     * <p>{@code oneOffIdx} 是确认页上勾了「一次性」的行号集合。
     * 为什么按行号而不是在 {@code Line} 里加字段:{@code Line} 是<b>解析结果</b>,
     * 由渠道解析器产出;「是不是一次性」是<b>用户在确认页上的决定</b>,
     * 两者来源不同、生命周期不同,混在一个 record 里会让解析器看起来该猜这个值。</p>
     *
     * <p>PRD FR-613 要求逐笔录入与账单导入<b>两条路都要有</b> ——
     * 只做一条的后果不是「少个功能」,是用户在导入的那几十笔里没法标一次性,
     * 而大额一次性支出(装修、旅行)恰恰多半是刷卡来的。</p>
     */
    @Transactional
    public Result commit(long familyId, long memberId, long periodId, long fallbackAccountId,
                         ExpenseSource channel, List<BillCategoryResolver.Line> lines,
                         int notIncluded, int skipped, boolean affectsBalance,
                         java.util.Set<Integer> oneOffIdx) {
        Period period = periodMapper.findById(familyId, periodId)
                .orElseThrow(() -> new CommitException("找不到这个账期,刷新一下再试。"));
        if (period.getFamilyId() == null || period.getFamilyId() != familyId) {
            throw new CommitException("这个账期不属于你家。");
        }
        if (!"OPEN".equalsIgnoreCase(String.valueOf(period.getStatus()))) {
            throw new CommitException("这个账期已经关账了 —— 导入会改动已经定稿的数。"
                    + "要补录的话先把账期打开。");
        }
        /* 【每一笔各自的账户】—— 一份账单里「收/付款方式」是变化的(FR-575)。
         * 这里把用到的账户一次查出来校验,而不是逐笔查:190 笔可能只涉及 3 个账户。 */
        Map<Long, Account> used = new LinkedHashMap<>();
        for (BillCategoryResolver.Line l : lines) {
            Long id = l.accountId() != null ? l.accountId() : fallbackAccountId;
            if (id == null || used.containsKey(id)) continue;
            Account a = accountMapper.findById(familyId, id)
                    .orElseThrow(() -> new CommitException("有一笔落到了找不到的账户,刷新一下再试。"));
            if (a.getFamilyId() == null || a.getFamilyId() != familyId) {
                throw new CommitException("有一笔落到了不属于你家的账户。");
            }
            if (a.getArchivedAt() != null) {
                /* 归档账户不参与任何统计 —— 落进去的钱在【所有】口径里都看不见,
                 * 是静默丢数据,比看得见的错更糟(与 EntryService.recordExpense 同一条判据)。 */
                throw new CommitException("账户「" + a.getDisplayName() + "」已归档,"
                        + "导进去的钱在所有报表里都会看不见。把那几笔改到在用的账户。");
            }
            used.put(id, a);
        }
        if (used.isEmpty()) throw new CommitException("没有指定任何账户 —— 先在确认页选一个。");
        Account acct = used.values().iterator().next();

        /* v1.19.3 的规则在导入这条路上同样成立:负债账户(信用卡)上记「还贷 / 利息支出」
         * 会和这张卡上的消费重复计入本月支出。手工录入那边由 expense-liability.js + 服务端一起挡,
         * 导入这边只有服务端能挡 —— 用户选账户时看不到自己这一批里有几笔还贷。 */
        for (BillCategoryResolver.Line l : lines) {
            if (l.bucket() != BillCategoryResolver.Bucket.NATURE) continue;
            Account a = used.get(l.accountId() != null ? l.accountId() : fallbackAccountId);
            if (a != null && a.getType() != null && a.getType().isLiability()) {
                throw new CommitException("有一笔「还贷」落在负债账户「" + a.getDisplayName()
                        + "」上 —— 还贷要记在钱实际流出的现金账户上,"
                        + "记在卡上会和这张卡的消费重复计入本月支出。把那笔改到现金账户。");
            }
        }

        List<BillCategoryResolver.Line> keep = lines.stream()
                .filter(l -> l.bucket() == BillCategoryResolver.Bucket.SPEND
                          || l.bucket() == BillCategoryResolver.Bucket.NATURE)
                .toList();
        if (keep.isEmpty()) throw new CommitException("一笔都没勾 —— 在表格里勾上要录的那些再提交。");
        /* v1.22 · 【这里原来有一道「金额 ≤ 0 就拒收」】,因为 cash_flow 上有 CHECK(amount > 0)。
         * V60 把那条约束去掉了:0 元(全额优惠/积分抵扣)和负数(退款冲正)都是真实存在的行,
         * 挡掉它们等于替用户决定哪些交易「不算数」。
         *
         * 仍然要防的是 null —— 那不是「0 元」,那是解析没读出金额,落库会 NPE。 */
        for (BillCategoryResolver.Line l : keep) {
            if (l.amount() == null) {
                throw new CommitException("有一笔的金额没读出来(" + l.merchant()
                        + ")—— 取消勾选它再提交,或者把文件发给我们看看。");
            }
        }
        if (keep.size() > MAX_ROWS) {
            throw new CommitException("一次最多导 " + MAX_ROWS + " 笔(这批有 " + keep.size() + " 笔)。"
                    + "按月分开导 —— 整年一次导进来,落错账期就没法收拾了。");
        }

        BigDecimal total = keep.stream().map(BillCategoryResolver.Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        /* 批次只记一个「主账户」(笔数最多的那个)—— 它现在是展示用的标签,不是落库依据。
         * 真正的归属在每一行上。 */
        Long mainAccountId = keep.stream()
                .map(l -> l.accountId() != null ? l.accountId() : fallbackAccountId)
                .collect(java.util.stream.Collectors.groupingBy(x -> x, java.util.stream.Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(fallbackAccountId);

        ExpenseImportBatch batch = ExpenseImportBatch.builder()
                .familyId(familyId).periodId(periodId).accountId(mainAccountId)
                .channel(channel).rowCount(keep.size()).totalAmount(total)
                .droppedCount(notIncluded).skippedCount(skipped).importedBy(memberId)
                .build();
        batchMapper.insert(batch);

        for (BillCategoryResolver.Line l : keep) {
            boolean nature = l.bucket() == BillCategoryResolver.Bucket.NATURE;
            String code = nature ? l.natureCode() : "consumption";
            /* 分类只在 consumption 下写 —— 与 EntryService 同一条规则:
             * 脏数据一旦落库,之后每处读它的地方都要重复同一个 if,总有一处会漏。 */
            Long catId = (!nature && categoryService.isUsable(familyId, l.categoryId()))
                    ? l.categoryId() : null;
            Long rowAcct = l.accountId() != null ? l.accountId() : fallbackAccountId;
            cashFlowMapper.insertOwned(familyId, CashFlow.builder()
                    .periodId(periodId)
                    .accountId(rowAcct)
                    .kind(CashFlowKind.EXPENSE)
                    .categoryCode(code)
                    .amount(l.amount())
                    .occurredAt(inPeriod(l.occurredAt(), period))
                    .note(trimNote(l.merchant()))
                    /* v1.24 FR-613 · 与 EntryService 同一条规则:只有消费才谈得上一次性 */
                    .oneOff(!nature && oneOffIdx.contains(l.idx()))
                    .submittedBy(memberId)
                    .sourceTag(channel.name())
                    .expenseCategoryId(catId)
                    .importBatchId(batch.getId())
                    .extTxNo(l.txNo())
                    .affectsBalance(affectsBalance)
                    .build());
        }

        /* 余额一次扣完,不是每笔扣一次 —— 300 笔逐个扣会写 300 条余额变更,
         * 而这在用户眼里本来就是【一个】动作。
         *
         * affectsBalance=false 时整步跳过:用户说「这笔钱已经从余额里扣过了」。
         * 这是导入最容易出错的地方 —— applyDeltaToBalance 改写的是用户自己填的期末余额,
         * 已经核对过余额的人再导一批,余额会被扣第二遍,而且几百笔一起扣,错得很大。 */
        if (affectsBalance) {
            /* 【按账户分别扣】—— 一批里可能有 3 个账户,一股脑扣到一个头上就是把
             * 别的账户的钱算到它身上。仍然是「每个账户一次」,不是每笔一次。 */
            Map<Long, BigDecimal> byAccount = new LinkedHashMap<>();
            for (BillCategoryResolver.Line l : keep) {
                Long id = l.accountId() != null ? l.accountId() : fallbackAccountId;
                byAccount.merge(id, l.amount(), BigDecimal::add);
            }
            for (var e : byAccount.entrySet()) {
                entryService.applyImportedExpense(familyId, memberId, period, used.get(e.getKey()),
                        e.getValue(), channel.getLabel() + " 导入");
            }
        }

        log.info("账单导入落库 · family={} period={} channel={} rows={} notIncluded={} skipped={} affectsBalance={}",
                familyId, periodId, channel, keep.size(), notIncluded, skipped, affectsBalance);
        return new Result(batch.getId(), keep.size(), total, notIncluded, skipped);
    }

    /**
     * 整批撤销(FR-539)。
     *
     * <p>软删该批次落的所有流水 + 把钱加回账户余额。<b>不是硬删</b> ——
     * 全站都按 {@code deleted_at IS NULL} 过滤,软删足够,而硬删会让「导错了又撤销」
     * 这件事在审计上无迹可寻。</p>
     */
    @Transactional
    public int revoke(long familyId, long memberId, long batchId) {
        ExpenseImportBatch b = batchMapper.find(familyId, batchId);
        if (b == null) throw new CommitException("找不到这个批次。");
        if (b.getRevokedAt() != null) throw new CommitException("这批已经撤销过了。");
        Period period = periodMapper.findById(familyId, b.getPeriodId())
                .orElseThrow(() -> new CommitException("找不到这个账期。"));
        /* 【只有当初扣过余额的批次才加回】—— 否则「不落账户」的批次一撤销,
         * 余额会凭空多出一笔钱。判据取该批次实际落的行:它们的 affects_balance 是一致的。 */
        boolean hadBalance = flowMapper.batchAffectsBalance(familyId, batchId);
        /* 【按账户分别加回】—— 一批可能跨几个账户,全加回批次的「主账户」会把
         * 别的账户的钱塞给它。必须在软删【之前】统计,软删之后就查不到了。 */
        var perAccount = hadBalance ? flowMapper.batchAmountByAccount(familyId, batchId) : List.<com.family.finance.repository.ExpenseFlowMapper.AcctSum>of();
        int n = flowMapper.softDeleteBatch(familyId, batchId);
        batchMapper.markRevoked(familyId, batchId);
        for (var a : perAccount) {
            Account acct = accountMapper.findById(familyId, a.accountId()).orElse(null);
            if (acct == null) continue;
            entryService.applyImportedExpense(familyId, memberId, period, acct,
                    a.amount().negate(), "撤销导入批次 #" + batchId);
        }
        return n;
    }

    /**
     * 交易日期落在账期外时,夹回账期内。
     *
     * <p>账单里 9 月 30 日 23:50 的一笔,渠道按付款时间算可能记成 10 月 1 日。
     * 让它落在账期外的后果是这笔钱<b>在两个月的报表里都不出现</b>(报表按 period_id 取数,
     * 但 occurred_at 会让人以为它在下个月)—— 夹回来,并且日期本身照实存不了就用账期末。</p>
     */
    static LocalDate inPeriod(LocalDate at, Period p) {
        if (at == null) return p.getPeriodEnd();
        if (p.getPeriodStart() != null && at.isBefore(p.getPeriodStart())) return p.getPeriodStart();
        if (p.getPeriodEnd() != null && at.isAfter(p.getPeriodEnd())) return p.getPeriodEnd();
        return at;
    }

    /** 备注列有长度上限,商户名可能很长(「XX有限公司(XX路XX号店)」) */
    static String trimNote(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() <= 80 ? t : t.substring(0, 80);
    }
}
