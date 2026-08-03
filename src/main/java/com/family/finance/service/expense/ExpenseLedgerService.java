package com.family.finance.service.expense;

import com.family.finance.domain.family.ExpenseEntryMode;
import com.family.finance.repository.CashFlowMapper;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.8 · 家庭支出的**唯一口径入口**。
 *
 * <h3>为什么必须只有一个入口</h3>
 * v1.6.29 踩过一次:同一屏上「家庭 XIRR」只读逐账户流水、「人赚」走成员手填收支,
 * 两个 KPI 互相矛盾,用户来报障。根因就是**口径判断散落在各调用点**。
 * 所以本版把支出口径收敛到这里,10 个调用方都必须经过它,守护 {@code v18-EXPENSE-ONE-SOURCE}
 * 断言那些文件里不再直接出现 {@code totalExpense()}。
 *
 * <h3>优先级 —— <b>必须受 expense_entry_mode 约束</b></h3>
 * <pre>
 * ITEMIZED 模式:逐笔 &gt; 0 → 取逐笔;否则 PMC &gt; 0 → 取总额;否则 NONE
 * TOTAL    模式:PMC &gt; 0  → 取总额;否则 NONE(**不用逐笔兜底** · 由调用方回落原路径)
 * </pre>
 * <b>两者永不相加</b>。判据用「合计 &gt; 0」而不是「有行」——
 * 一行金额为 0 的逐笔不应该压掉用户手填的总额。
 *
 * <h3>⚠ 为什么优先级不能无条件「逐笔优先」(开发中被拦下的 bug)</h3>
 * PRD FR-273 初稿写的是无条件逐笔优先。第 3 步做完跑「总额模式逐位比对」时被拦下:
 * <ul>
 *   <li>beta:紧急储备 8.0 月 → 1.9 月(月均支出凭空涨 4 倍)</li>
 *   <li>prod 复核:2026-06 的 PMC 总额 ¥32,797,而该期逐笔只有 ¥3,000
 *       —— 无条件逐笔优先会让当月支出变成 3,000,<b>少算 89%</b>,
 *       连带污染储蓄率 / 月均支出 / 紧急储备 / 人赚钱赚 / XIRR / 应急目标基线。</li>
 * </ul>
 * 根因:{@code cash_flow} 里本来就有 EXPENSE 行 —— 它们来自**账户级流水**那条老路径,
 * 而不是「用户主动选择逐笔录入」。把它们当成"用户的逐笔支出"是误读。
 * 所以逐笔优先只在家庭<b>显式切到 ITEMIZED</b> 后才生效;TOTAL 模式下行为与 v1.8 之前逐位相同。
 *
 * <p>连带影响一:TOTAL 模式下**取期集合也必须与旧行为一致**(只看 PMC 有记录的期),
 * 不能并上「只有逐笔的期」—— 否则月均支出的分母变了,即使每期取值都对,均值也会不同。</p>
 *
 * <p>连带影响二:**PMC 的取数来源也不能随手换**。单期查询走 {@code findFamilyAggregateForPeriod}
 * (原 netInflowExpense 用的),近 N 期走 {@code findFamilyAggregateRecent}(原 averageExpense 用的)。
 * 两者并不等价 —— 后者带 {@code HAVING filledMembers > 0} 和 {@code LIMIT}。
 * 开发中一度把单期也改走 Recent,4 条既有单测立刻挂掉。</p>
 *
 * <h3>⚠ 与收入侧优先级相反</h3>
 * 收入侧是「PMC 手填 &gt; 0 则用之,否则用 cash_flow 汇总」,
 * 支出侧是「逐笔 &gt; 0 则用之,否则用 PMC」。
 * <b>方向是反的</b>,这是历史造成的:收入的历史事实存在 PMC 里,而支出的更细事实存在逐笔里。
 * 这条反直觉的不一致必须同时出现在四处:本类注释、{@code netInflowIncome/Expense} 的方法注释、
 * 页面 tooltip、{@code docs/how-to-record.md}。少写一处,下一个人就会靠猜。
 *
 * <h3>口径 A vs 口径 B</h3>
 * 本类返回的是**口径 A · 家庭支出**(排除 {@code is_adjustment=1} 的现金调整)。
 * 账户级 PnL / NAV / 回撤用的是**口径 B · 账户外部流出**(含调整,见 {@code FactMapper.queryBase}
 * 的 {@code expense_orig}),那条一行不动。两者不可互换。
 *
 * <p>本服务**只依赖 mapper、不依赖任何 service** —— 这样 FactViewServiceImpl / GoalService /
 * HouseholdCashflowService / CheckupController 都能安全注入而不会成环。</p>
 *
 * <p>返回值一律**本位币**。视图币种由调用方乘 baseToViewFactor(与 PMC 现行做法一致),
 * 不在本服务里掺币种逻辑。</p>
 */
@Service
@RequiredArgsConstructor
public class ExpenseLedgerService {

    private final CashFlowMapper cashFlowMapper;
    private final PeriodMemberCashflowMapper pmcMapper;
    /** v1.8 · 读家庭的 expense_entry_mode —— 优先级必须受它约束,见类注释「为什么优先级不能无条件」 */
    private final FamilyMapper familyMapper;
    /** v1.8 · 只用来取「进行中账期」,判定近 N 期的上边界 —— 见 latestRecordableStart */
    private final com.family.finance.repository.PeriodMapper periodMapper;

    /**
     * 一期支出的口径结果。source 让页面能说清「用的是哪一种」,itemCount 供文案显示「逐笔 · N 笔」。
     *
     * <p>{@code periodStart} 只有 {@link #recent} 会填(它本来就握着日期);
     * {@link #byPeriod}/{@link #byPeriods} 是按 id 点查,不额外为了日期再查一次表 —— 那里是 null。
     * 需要按时间排序/打标签的调用方请用 {@code recent}。</p>
     */
    public record PeriodExpense(Long periodId, java.time.LocalDate periodStart,
                                BigDecimal amountBase, Source source, int itemCount) {
        public enum Source { ITEMIZED, TOTAL, NONE }

        public boolean filled() { return source != Source.NONE; }

        public static PeriodExpense none(Long periodId) {
            return new PeriodExpense(periodId, null, BigDecimal.ZERO, Source.NONE, 0);
        }

        PeriodExpense withStart(java.time.LocalDate start) {
            return new PeriodExpense(periodId, start, amountBase, source, itemCount);
        }
    }

    /** 该家庭当前的支出录入方式(脏值/缺失兜底 TOTAL)。 */
    public ExpenseEntryMode modeOf(long familyId) {
        return familyMapper.findById(familyId)
                .map(f -> ExpenseEntryMode.fromCode(f.getExpenseEntryMode()))
                .orElse(ExpenseEntryMode.TOTAL);
    }

    /** 批量取,避免 N+1。返回 periodId → 口径结果;传入的每个 periodId 都会有一项(可能是 NONE)。 */
    public Map<Long, PeriodExpense> byPeriods(long familyId, Collection<Long> periodIds) {
        Map<Long, PeriodExpense> out = new LinkedHashMap<>();
        if (periodIds == null || periodIds.isEmpty()) return out;
        boolean itemizedFirst = modeOf(familyId) == ExpenseEntryMode.ITEMIZED;

        Map<Long, CashFlowMapper.RealExpenseSum> itemized = new HashMap<>();
        for (CashFlowMapper.RealExpenseSum r : cashFlowMapper.sumRealExpenseByPeriod(familyId, periodIds)) {
            if (r.periodId() != null) itemized.put(r.periodId(), r);
        }
        for (Long pid : periodIds) {
            // 单期 PMC 走 findFamilyAggregateForPeriod —— 与 v1.8 之前 netInflowExpense 的取数来源
            // **完全相同**。别改用 findFamilyAggregateRecent:那条带 HAVING filledMembers > 0,
            // 而且按期点查还得 LIMIT 全表扫,既换了口径又拖慢热路径。
            BigDecimal total = pmcMapper.findFamilyAggregateForPeriod(pid)
                    .map(a -> a.totalExpense()).orElse(null);
            out.put(pid, decide(pid, itemized.get(pid), total, itemizedFirst));
        }
        return out;
    }

    /** 已经握着 PMC 聚合结果时用这个,避免再点查一遍。 */
    private Map<Long, PeriodExpense> decideWith(long familyId, List<Long> periodIds,
                                                Map<Long, BigDecimal> totals, boolean itemizedFirst) {
        Map<Long, CashFlowMapper.RealExpenseSum> itemized = new HashMap<>();
        for (var r : cashFlowMapper.sumRealExpenseByPeriod(familyId, periodIds)) {
            if (r.periodId() != null) itemized.put(r.periodId(), r);
        }
        Map<Long, PeriodExpense> out = new LinkedHashMap<>();
        for (Long pid : periodIds) {
            out.put(pid, decide(pid, itemized.get(pid), totals.get(pid), itemizedFirst));
        }
        return out;
    }

    public PeriodExpense byPeriod(long familyId, long periodId) {
        return byPeriods(familyId, List.of(periodId)).getOrDefault(periodId, PeriodExpense.none(periodId));
    }

    /**
     * 近 N 期(按 period_start 倒序 → 结果按倒序返回),供月均 / 中位数 / 已填月数用。
     *
     * <p><b>取期集合按模式区分</b>:</p>
     * <ul>
     *   <li><b>TOTAL</b>:只看 PMC 有记录的期 —— 与 v1.8 之前<b>逐位一致</b>(分母不能变)</li>
     *   <li><b>ITEMIZED</b>:PMC 期 ∪ 有逐笔支出的期 —— 否则「只录逐笔、没填总额」的月份整个漏掉,
     *       月均支出偏小、已填月数偏少</li>
     * </ul>
     */
    public List<PeriodExpense> recent(long familyId, int limit) {
        boolean itemizedFirst = modeOf(familyId) == ExpenseEntryMode.ITEMIZED;

        // ① PMC 侧有记录的期 —— 取数来源与 v1.8 之前的 averageExpense / medianMonthlySavings 相同。
        //    多取一些(limit×3)是因为 ITEMIZED 模式下要与逐笔期合并后重新截断。
        var pmcRecent = pmcMapper.findFamilyAggregateRecent(familyId, Math.max(limit * 3, limit));
        Map<Long, java.time.LocalDate> startById = new LinkedHashMap<>();
        Map<Long, BigDecimal> totals = new HashMap<>();
        for (var a : pmcRecent) {
            startById.put(a.periodId(), a.periodStart());
            if (a.totalExpense() != null) totals.put(a.periodId(), a.totalExpense());
        }

        // ② 逐笔独有的期 · 仅 ITEMIZED 模式并入(TOTAL 模式并进来会改掉月均的分母)
        //    并且**不并入未来账期**:账期表可能预建到很多年以后(beta 排到了 2038),
        //    「近 12 期」若把 2029 年的一笔算进来,月均支出/紧急储备就不是「近 12 个月」了。
        //    边界取「今天」与「进行中账期的起始」的较晚者 —— 家庭可以提前开下一期并开始录,
        //    那一期是用户心里的当期,不能因为 period_start 在未来就被剔掉。
        //    只约束这条新增路径 —— PMC 那条查询一行不动,总额模式才能逐位不变。
        if (itemizedFirst) {
            java.time.LocalDate today = latestRecordableStart(familyId);
            for (var r : cashFlowMapper.sumRealExpenseByPeriod(familyId, null)) {
                if (r.periodId() == null) continue;
                if (r.periodStart() != null && r.periodStart().isAfter(today)) continue;
                startById.putIfAbsent(r.periodId(), r.periodStart());
            }
        }
        if (startById.isEmpty()) return List.of();

        List<Long> ordered = new ArrayList<>(startById.keySet());
        ordered.sort((x, y) -> {
            var sx = startById.get(x);
            var sy = startById.get(y);
            if (sx == null && sy == null) return Long.compare(y, x);   // 都没日期 → 用 id 倒序兜底
            if (sx == null) return 1;
            if (sy == null) return -1;
            return sy.compareTo(sx);
        });

        Map<Long, PeriodExpense> all = decideWith(familyId, ordered, totals, itemizedFirst);
        List<PeriodExpense> out = new ArrayList<>();
        for (Long pid : ordered) {
            PeriodExpense pe = all.get(pid);
            if (pe == null || !pe.filled()) continue;      // 与现行语义一致:没填的期不参与均值/中位
            // TOTAL 分支的日期来自 PMC 聚合,ITEMIZED 分支的来自逐笔查询 —— 两边都补齐,
            // 否则调用方拿不到日期只能退化成按 id 排序/打标签(图表 x 轴会乱序)。
            out.add(pe.periodStart() == null ? pe.withStart(startById.get(pid)) : pe);
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** v1.8 FR-272 · 支出构成的一个可切维度。参数不进 SQL 结构,只用白名单枚举。 */
    public enum Dim {
        CATEGORY("category", "按类目", "刚性支出(还贷 + 利息)vs 日常开支 —— 刚性压不下去,日常才是可调的"),
        ACCOUNT("account", "按账户", "钱主要从哪张卡出去"),
        MEMBER("member", "按成员", "家庭内部对账用");

        private final String code;
        private final String displayName;
        private final String hintText;
        Dim(String code, String displayName, String hintText) {
            this.code = code; this.displayName = displayName; this.hintText = hintText;
        }
        public String code() { return code; }
        public String displayName() { return displayName; }
        public String hintText() { return hintText; }
        public static Dim fromCode(String c) {
            if (c != null) for (Dim d : values()) if (d.code.equalsIgnoreCase(c.trim())) return d;
            return CATEGORY;
        }
    }

    /**
     * v1.8 FR-272 · 支出构成。
     *
     * @param periodIds 参与统计的账期(调用方按「本期 / 近 6 期 / 近 12 期」给)
     * @return 分组 + 占比 + 「只填了总数、没有构成」的账期清单(不隐藏、不假装没有)
     */
    public Composition composition(long familyId, Collection<Long> periodIds, Dim dim) {
        if (periodIds == null || periodIds.isEmpty()) {
            return new Composition(dim, List.of(), BigDecimal.ZERO, List.of());
        }
        var groups = cashFlowMapper.expenseBreakdown(familyId, periodIds, dim.code());
        BigDecimal total = BigDecimal.ZERO;
        for (var g : groups) if (g.amountBase() != null) total = total.add(g.amountBase());

        List<Slice> slices = new ArrayList<>();
        for (var g : groups) {
            BigDecimal amt = g.amountBase() == null ? BigDecimal.ZERO : g.amountBase();
            BigDecimal pct = total.signum() == 0 ? BigDecimal.ZERO
                    : amt.multiply(new BigDecimal("100")).divide(total, 1, RoundingMode.HALF_EVEN);
            slices.add(new Slice(g.groupKey(), g.groupLabel(),
                    amt.setScale(2, RoundingMode.HALF_EVEN), pct, g.itemCount()));
        }

        // 哪些账期只有总额、没有逐笔 —— 页面要如实标出来,否则用户会以为构成覆盖了全部支出
        var byPeriod = byPeriods(familyId, periodIds);
        List<Long> totalOnly = new ArrayList<>();
        for (Long pid : periodIds) {
            var pe = byPeriod.get(pid);
            if (pe != null && pe.source() == PeriodExpense.Source.TOTAL) totalOnly.add(pid);
        }
        return new Composition(dim, slices, total.setScale(2, RoundingMode.HALF_EVEN), totalOnly);
    }

    /** 一格构成。pct 是 0–100 的百分数(图上直接标数字,不靠 hover)。 */
    public record Slice(String groupKey, String label, BigDecimal amountBase,
                        BigDecimal pct, int itemCount) {}

    public record Composition(Dim dim, List<Slice> slices, BigDecimal totalBase, List<Long> totalOnlyPeriodIds) {
        public boolean hasData() { return !slices.isEmpty() && totalBase.signum() > 0; }
        public boolean hasTotalOnlyPeriods() { return !totalOnlyPeriodIds.isEmpty(); }
    }

    /**
     * 「可计入的最晚账期起始」= 今天与**进行中账期起始**的较晚者。
     * 家庭可以提前开下一期并开始录(8 月就把 9 月开出来),那一期是用户心里的当期,
     * 不能因为 period_start 落在未来就被「不超过今天」剔掉。
     */
    private java.time.LocalDate latestRecordableStart(long familyId) {
        java.time.LocalDate today = java.time.LocalDate.now();
        return periodMapper.findCurrentOpen(familyId)
                .map(p -> p.getPeriodStart())
                .filter(start -> start.isAfter(today))
                .orElse(today);
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private PeriodExpense decide(Long periodId, CashFlowMapper.RealExpenseSum itemized,
                                 BigDecimal total, boolean itemizedFirst) {
        boolean hasItem = itemized != null && itemized.amount() != null && itemized.amount().signum() > 0;
        boolean hasTotal = total != null && total.signum() > 0;
        PeriodExpense item = hasItem
                ? new PeriodExpense(periodId, itemized.periodStart(), itemized.amount(),
                                    PeriodExpense.Source.ITEMIZED, itemized.itemCount())
                : null;
        PeriodExpense tot = hasTotal
                ? new PeriodExpense(periodId, null, total, PeriodExpense.Source.TOTAL, 0)
                : null;
        if (itemizedFirst) {
            if (item != null) return item;
            if (tot != null) return tot;
        } else if (tot != null) {
            return tot;
        }
        // TOTAL 模式且 PMC 缺失 → **故意**返回 NONE,不用逐笔兜底。
        // 让调用方回落到它自己原来的路径(periodExpense / avgFromCashFlow —— 都读事实切片),
        // 这样「总额模式逐位不变」是结构上成立的,而不是靠我把 SQL 逐条对齐事实表的过滤与汇率。
        // 开发中先试过在这里兜底,结果 beta 家庭 XIRR 从 −56.19% 漂到 −50.60%(归档账户 + 汇率两处不一致)。
        return PeriodExpense.none(periodId);
    }
}
