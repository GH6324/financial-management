package com.family.finance.service.expense;

import com.family.finance.domain.expense.ExpenseCategory;
import com.family.finance.domain.expense.ExpenseImportBatch;
import com.family.finance.domain.expense.ExpenseSource;
import com.family.finance.domain.expense.ExpenseSplit;
import com.family.finance.repository.ExpenseCategoryMapper;
import com.family.finance.repository.ExpenseImportBatchMapper;
import com.family.finance.repository.ExpenseSplitMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.21 · <b>分类金额的唯一写入口</b>,以及「Σ 分类 = 月度总额」这条恒等式的守门人。
 *
 * <h3>为什么必须只有一个写入口</h3>
 *
 * <p>本项目已经为「口径来源多一条」付过代价:v1.8 之前支出口径散落在各调用点,
 * 同一屏上两个 KPI 互相矛盾;后来收敛到 {@code ExpenseLedgerService} 才治好
 * (那个类的注释里记着「无条件逐笔优先」会让月均支出少算 89%)。</p>
 *
 * <p>所以这一版<b>绝不给家庭支出加第三条来源</b>。结构是这样保证的:</p>
 *
 * <pre>
 *   分类填报 = TOTAL 模式「那一个总数」的【展开形式】
 *   Σ(expense_split) ──同一事务──▶ period_member_cashflow.total_expense_input
 *   ExpenseLedgerService 照旧只读 PMC 与逐笔 —— 【一行不改】
 * </pre>
 *
 * <p>换句话说:分类明细<b>根本不在家庭支出口径的读取范围里</b>。它只喂支出报表。
 * 这不是靠测试保证的,是结构上必然的 —— 护栏 {@code v1210-LEDGER-UNTOUCHED} 钉着那个文件的 diff 为空。</p>
 *
 * <h3>两级恒等式</h3>
 *
 * <pre>
 *   ① 类目显示额 = Σ(该类目的各来源行)      ← 来源行模型
 *   ② PMC 月度总额 = Σ(所有类目显示额)      ← 本类每次写完在同事务重算
 * </pre>
 *
 * <h3>手工修正永远不被导入冲掉</h3>
 *
 * <p>手填是一条<b>独立的来源行</b>({@code MANUAL}),不是渠道的从属。于是:</p>
 * <ul>
 *   <li>重导渠道 X = 按(期,人,X)整批删再插 → <b>MANUAL 行不在删除范围里</b></li>
 *   <li>用户直接改类目金额 = 写 MANUAL 行 = 输入值 − Σ(渠道行),<b>可为负</b>(对导入值的冲正)</li>
 * </ul>
 * <p>这是整个合并模型里最该被守住的一条 —— 「我改过的数被一次重导抹了」是最伤信任的失败。</p>
 */
@Service
@RequiredArgsConstructor
public class ExpenseSplitService {

    private final ExpenseSplitMapper splitMapper;
    private final ExpenseCategoryMapper categoryMapper;
    private final ExpenseImportBatchMapper batchMapper;
    private final PeriodMemberCashflowMapper pmcMapper;
    private final ExpenseCategoryService categoryService;

    public static class SplitException extends RuntimeException {
        public SplitException(String m) { super(m); }
    }

    // ──────────────────────── 读:一格的来源构成 ────────────────────────

    /** 一个类目在(期×人)上的样子:合成额 + 各来源明细(页面角标用) */
    public record CategoryCell(long categoryId, BigDecimal total,
                               Map<ExpenseSource, BigDecimal> bySource) {
        public BigDecimal imported() {
            BigDecimal s = BigDecimal.ZERO;
            for (var e : bySource.entrySet()) if (e.getKey().isImported()) s = s.add(e.getValue());
            return s;
        }
        public BigDecimal manual() { return bySource.getOrDefault(ExpenseSource.MANUAL, BigDecimal.ZERO); }
    }

    public Map<Long, CategoryCell> cells(long periodId, long memberId) {
        Map<Long, Map<ExpenseSource, BigDecimal>> acc = new LinkedHashMap<>();
        for (ExpenseSplit r : splitMapper.findByPeriodMember(periodId, memberId)) {
            acc.computeIfAbsent(r.getCategoryId(), k -> new LinkedHashMap<>())
               .merge(r.getSource(), nz(r.getAmount()), BigDecimal::add);
        }
        Map<Long, CategoryCell> out = new LinkedHashMap<>();
        acc.forEach((cid, bySource) -> {
            BigDecimal total = BigDecimal.ZERO;
            for (BigDecimal v : bySource.values()) total = total.add(v);
            out.put(cid, new CategoryCell(cid, total, bySource));
        });
        return out;
    }

    /** 这个人这一期有没有展开填过 —— 用来决定填报页显示展开态还是收起态 */
    public boolean hasSplits(long periodId, long memberId) {
        return !splitMapper.findByPeriodMember(periodId, memberId).isEmpty();
    }

    // ──────────────────────── 写:手填 ────────────────────────

    /**
     * 保存展开态表单。{@code wanted} 是「用户希望每个类目显示成多少」。
     *
     * <p>写入的是 <b>MANUAL 行 = 期望值 − Σ(该类目已有的渠道行)</b>。
     * 于是导入行原样保留可审计,而用户看到的就是他填的数。差额为负时说明他在冲正导入值,允许。</p>
     *
     * <p>期望值本身不允许为负 —— 「这个月餐饮花了 −200」没有意义,而且会让报表出现负扇区。</p>
     */
    @Transactional
    public void saveManual(long familyId, long periodId, long memberId, Map<Long, BigDecimal> wanted) {
        Map<Long, CategoryCell> before = cells(periodId, memberId);
        for (var e : wanted.entrySet()) {
            long cid = e.getKey();
            BigDecimal want = e.getValue();
            if (want != null && want.signum() < 0) {
                throw new SplitException("金额不能是负数。要冲掉一笔多算的钱,把这个类目的数直接改小就行。");
            }
            BigDecimal imported = before.containsKey(cid) ? before.get(cid).imported() : BigDecimal.ZERO;
            BigDecimal manual = nz(want).subtract(imported);
            if (manual.signum() == 0 && (want == null || want.signum() == 0)) {
                // 期望 0 且没有渠道行 → 这一格干脆不留行,免得表里堆一堆 0
                if (imported.signum() == 0) { removeManual(periodId, memberId, cid); continue; }
            }
            splitMapper.upsert(ExpenseSplit.builder()
                    .familyId(familyId).periodId(periodId).memberId(memberId).categoryId(cid)
                    .source(ExpenseSource.MANUAL).amount(manual).build());
        }
        syncPmc(familyId, periodId, memberId);
    }

    private void removeManual(long periodId, long memberId, long categoryId) {
        splitMapper.deleteOne(periodId, memberId, categoryId, ExpenseSource.MANUAL.name());
    }

    /**
     * 「并回一个总数」:删掉这个人这一期的所有分类明细,<b>但把总额留在 PMC 里</b>。
     *
     * <p>刻意不清 PMC —— 用户要的是「不再拆」,不是「这个月没花钱」。</p>
     */
    @Transactional
    public void collapse(long familyId, long periodId, long memberId) {
        splitMapper.deleteByPeriodMember(periodId, memberId);
        // 批次也一并作废,否则导入页还会显示「本期已导过」而实际数据已清
        for (ExpenseImportBatch b : batchMapper.findLiveByPeriod(familyId, periodId)) {
            if (b.getMemberId() != null && b.getMemberId() == memberId) {
                batchMapper.revoke(familyId, b.getId());
            }
        }
        // PMC 不动:总额还是那个总额
    }

    // ──────────────────────── 写:导入 ────────────────────────

    /** 一次导入落地的结果 */
    public record BatchResult(long batchId, Long replacedId, BigDecimal total, int rows,
                              Map<Long, BigDecimal> byCategory) {}

    /**
     * 落一个导入批次。<b>同渠道同期只替换该渠道的行</b> —— 这一句是整个合并模型的核心。
     *
     * @param byCategory 类目 → 该渠道贡献的金额
     */
    @Transactional
    public BatchResult applyBatch(long familyId, long periodId, long memberId, Long operatorId,
                                  ExpenseSource channel, Map<Long, BigDecimal> byCategory, int rowCount) {
        if (channel == null || !channel.isImported()) {
            throw new SplitException("这不是一个导入渠道 —— 手填走另一条路。");
        }
        ExpenseImportBatch prev = batchMapper.findLive(familyId, periodId, memberId, channel.name());

        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal v : byCategory.values()) total = total.add(nz(v));

        ExpenseImportBatch batch = ExpenseImportBatch.builder()
                .familyId(familyId).periodId(periodId).memberId(memberId).channel(channel)
                .rowCount(rowCount).totalAmount(total)
                .replacedId(prev == null ? null : prev.getId())
                .importedBy(operatorId).build();
        batchMapper.insert(batch);
        if (prev != null) batchMapper.revoke(familyId, prev.getId());

        // ★ 只删这一个渠道的行。MANUAL 与其它渠道的行【不在删除范围里】
        splitMapper.deleteBySource(periodId, memberId, channel.name());
        for (var e : byCategory.entrySet()) {
            if (nz(e.getValue()).signum() == 0) continue;
            splitMapper.upsert(ExpenseSplit.builder()
                    .familyId(familyId).periodId(periodId).memberId(memberId)
                    .categoryId(e.getKey()).source(channel).amount(nz(e.getValue()))
                    .batchId(batch.getId()).build());
        }
        syncPmc(familyId, periodId, memberId);
        return new BatchResult(batch.getId(), batch.getReplacedId(), total, rowCount, byCategory);
    }

    /** 撤销批次:该渠道的行整批移除,其余来源自动回落(因为显示额本来就是求和) */
    @Transactional
    public void revokeBatch(long familyId, long batchId) {
        ExpenseImportBatch b = batchMapper.find(familyId, batchId);
        if (b == null || b.getRevokedAt() != null) throw new SplitException("这个批次已经撤掉了。");
        splitMapper.deleteBySource(b.getPeriodId(), b.getMemberId(), b.getChannel().name());
        batchMapper.revoke(familyId, batchId);
        syncPmc(familyId, b.getPeriodId(), b.getMemberId());
    }

    public List<ExpenseImportBatch> batches(long familyId, long periodId) {
        return batchMapper.findLiveByPeriod(familyId, periodId);
    }

    // ──────────────────────── 恒等式 ────────────────────────

    /**
     * 重算 Σ 并回写 PMC。<b>每一个写路径的最后一步都必须是它</b>。
     *
     * <p>用 {@code upsertExpenseOnly} 而不是那条通用 upsert —— 后者是整行覆盖语义,
     * 会把 {@code total_income_input} 抹成 NULL(TDD 待实测 3 的实测结果)。</p>
     */
    private void syncPmc(long familyId, long periodId, long memberId) {
        BigDecimal sum = splitMapper.sumByPeriodMember(periodId, memberId);
        if (sum != null && sum.signum() < 0) {
            throw new SplitException("这一期的分类合计算出来是负数 —— 不该发生,先别保存。"
                    + "如果你在冲正某个导入值,把那个类目的数改成 0 而不是负数。");
        }
        // 一行分类都没有时写 null —— 语义是「这个人没用分类填报」,而不是「花了 0 元」
        pmcMapper.upsertExpenseOnly(familyId, periodId, memberId,
                sum == null || sum.signum() == 0 ? null : sum);
    }

    /** 护栏与单测用:该期该人的分类合计 */
    public BigDecimal sumOf(long periodId, long memberId) {
        return nz(splitMapper.sumByPeriodMember(periodId, memberId));
    }

    // ──────────────────────── 报表读(按树 rollup) ────────────────────────

    /** 一个大类在某期的样子:合计 + 细类拆分(含「未细分」= 直接记在大类上的钱) */
    public record TopRollup(long topId, String name, BigDecimal total,
                            List<Leaf> leaves) {
        public record Leaf(String name, BigDecimal amount, boolean unsplit) {}
    }

    /**
     * 按树聚合某一期(全家)。
     *
     * <p>「未细分」在这里生成 —— 它<b>不是一条类目行</b>,而是「split 行落在一级上」这件事的显示名。
     * 所以简单版切复杂版时不需要搬任何数据:一级上的钱本来就在那,只是换了个叫法。</p>
     */
    public List<TopRollup> rollup(long familyId, long periodId) {
        List<ExpenseSplit> rows = splitMapper.findByPeriod(familyId, periodId);
        if (rows.isEmpty()) return List.of();

        Map<Long, ExpenseCategory> byId = new LinkedHashMap<>();
        for (ExpenseCategory c : categoryMapper.findByFamily(familyId)) byId.put(c.getId(), c);

        // 大类 → (细类名 → 金额);细类名为 null 表示「未细分」
        Map<Long, Map<String, BigDecimal>> acc = new LinkedHashMap<>();
        for (ExpenseSplit r : rows) {
            ExpenseCategory c = byId.get(r.getCategoryId());
            if (c == null) continue;                     // 类目被硬删过,数据孤儿:跳过而不是算进「其他」
            boolean leaf = !c.isTopLevel();
            Long topId = leaf ? c.getParentId() : c.getId();
            if (topId == null || !byId.containsKey(topId)) continue;
            acc.computeIfAbsent(topId, k -> new LinkedHashMap<>())
               .merge(leaf ? c.getName() : "", nz(r.getAmount()), BigDecimal::add);
        }

        List<TopRollup> out = new ArrayList<>();
        for (ExpenseCategory top : categoryMapper.findByFamily(familyId)) {
            if (!top.isTopLevel()) continue;
            Map<String, BigDecimal> m = acc.get(top.getId());
            if (m == null || m.isEmpty()) continue;
            BigDecimal total = BigDecimal.ZERO;
            List<TopRollup.Leaf> leaves = new ArrayList<>();
            for (var e : m.entrySet()) {
                total = total.add(e.getValue());
                boolean unsplit = e.getKey().isEmpty();
                leaves.add(new TopRollup.Leaf(unsplit ? "未细分" : e.getKey(), e.getValue(), unsplit));
            }
            leaves.sort((a, b) -> b.amount().compareTo(a.amount()));
            out.add(new TopRollup(top.getId(), top.getName(), total, leaves));
        }
        out.sort((a, b) -> b.total().compareTo(a.total()));
        return out;
    }

    /** 这个家有没有任何分类数据 —— 报表据此决定要不要渲染扩展区(零组态逐字一致) */
    public boolean hasAnySplit(long familyId) {
        return !splitMapper.findByFamily(familyId).isEmpty();
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
