package com.family.finance.service.expense;

import com.family.finance.domain.expense.ExpenseNature;
import com.family.finance.repository.CashFlowMapper;
import com.family.finance.repository.ExpenseFlowMapper;
import com.family.finance.service.config.FamilyConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * v1.24 FR-632/633 · <b>常态月均</b> —— 剔掉一次性支出之后的近 12 期月均。
 *
 * <h3>为什么单开一个服务,而不是给 ExpenseLedgerService 加个重载</h3>
 *
 * <p>{@code averageExpense} 是这个项目最多下游的指标之一:FIRE 目标、紧急储备月数、
 * 体检流动性都吃它。PRD 把「既有指标数字一个都不变」(FR-671)列为本版排第一的失败模式。
 * 在口径权威类里长出第二个月均,等于把风险直接放在那条路上 ——
 * 而这个项目有过 {@code findFamilyAggregateRecent} / {@code ForPeriod} 调错的前科,
 * 两个重载摆在一起迟早调错。</p>
 *
 * <p>所以常态月均住在这里,{@code averageExpense} 一个字节不动。</p>
 *
 * <h3>分母必须和月均支出同一批期(预检 SF4)</h3>
 *
 * <p>两个数并排显示,用户会去减。如果一个按 12 期、另一个按 11 期算,
 * 差额就不等于「被剔除的一次性支出」,而两个数各自看都「合理」,
 * <b>只有减出来才看得出错</b>。所以这里不自己取期,而是复用
 * {@link ExpenseLedgerService#recentClosed} 返回的<b>同一个期集合</b>。</p>
 *
 * <h3>开关的级联机制(FR-674)</h3>
 *
 * <p>开关关掉时这里返回 {@link Optional#empty()},于是紧急储备 tooltip /
 * 体检流动性 / 目标页应急金<b>三处回流读数自动消失</b> ——
 * 不需要每一处各自记得去判断开关。口径服务拿不到数就返回「没有」、交回原路径,
 * 这是这个项目反复确认过的做法。</p>
 */
@Service
@RequiredArgsConstructor
public class NormalExpenseService {

    /** FR-674 · 支出分析章节的总开关。默认开。 */
    public static final String K_EXPENSE_ANALYSIS = "expense_analysis_enabled";

    /** FR-632 · 常态月均的窗口,与月均支出同宽 */
    public static final int WINDOW = 12;

    private final ExpenseLedgerService ledgerService;
    private final CashFlowMapper cashFlowMapper;
    private final ExpenseFlowMapper expenseFlowMapper;
    private final ExpenseNatureService natureService;
    private final FamilyConfigService configService;

    /**
     * 常态月均的完整结果。
     *
     * @param periodIds       参与计算的账期(与月均支出<b>同一批</b>)
     * @param averageBase     月均支出 —— 直接来自 {@code recentClosed},不重算
     * @param normalBase      常态月均 = (窗口内总支出 − 一次性合计) ÷ 期数
     * @param oneOffTotalBase 窗口内一次性合计
     * @param oneOffCount     被剔除的笔数(FR-633 的「剔除了 N 笔」)
     */
    public record Normal(List<Long> periodIds, BigDecimal averageBase, BigDecimal normalBase,
                         BigDecimal oneOffTotalBase, int oneOffCount) {

        /** 有没有真的剔掉东西 —— 没有时页面只显示一个数,不并排显示两个一样的 */
        public boolean differs() { return oneOffCount > 0 && oneOffTotalBase.signum() != 0; }

        /**
         * SF4 的自检:月均支出 − 常态月均,应当恰好等于 一次性合计 ÷ 期数。
         * 单测直接断言这个恒等式;这里留一个方法让护栏和调用方都能引用同一个表达式。
         */
        public BigDecimal expectedGap() {
            if (periodIds.isEmpty()) return BigDecimal.ZERO;
            return oneOffTotalBase.divide(BigDecimal.valueOf(periodIds.size()), 2, RoundingMode.HALF_EVEN);
        }
    }

    /** FR-674 · 这个家有没有打开支出分析。默认开。 */
    public boolean enabled(long familyId) {
        return configService.getBoolean(familyId, K_EXPENSE_ANALYSIS, true);
    }

    /**
     * FR-632 · 常态月均。
     *
     * <p>返回 {@code empty()} 的三种情况,都不是错误:</p>
     * <ul>
     *   <li>FR-674 开关关着</li>
     *   <li>窗口里一期都没有(这个家还没有完整月份的支出记录)</li>
     *   <li>这个家是 TOTAL 模式(只填总额,没有逐笔)—— 无从判断哪笔是一次性的</li>
     * </ul>
     */
    public Optional<Normal> normal(long familyId) {
        if (!enabled(familyId)) return Optional.empty();

        List<ExpenseLedgerService.PeriodExpense> window = ledgerService.recentClosed(familyId, WINDOW);
        if (window.isEmpty()) return Optional.empty();

        // 只有逐笔模式才谈得上「哪一笔是一次性的」 —— TOTAL 模式下这个概念不存在
        boolean anyItemized = window.stream()
                .anyMatch(pe -> pe.source() == ExpenseLedgerService.PeriodExpense.Source.ITEMIZED);
        if (!anyItemized) return Optional.empty();

        List<Long> ids = window.stream().map(ExpenseLedgerService.PeriodExpense::periodId).toList();

        BigDecimal total = BigDecimal.ZERO;
        for (var pe : window) total = total.add(nz(pe.amountBase()));
        BigDecimal average = total.divide(BigDecimal.valueOf(ids.size()), 2, RoundingMode.HALF_EVEN);

        Map<Long, ExpenseNature> natures = natureService.natureMap(familyId);
        BigDecimal oneOff = BigDecimal.ZERO;
        int oneOffRows = 0;
        for (var row : cashFlowMapper.sumExpenseByPeriodCategoryOneOff(familyId, ids)) {
            if (natureService.natureOf(row.oneOff(), row.expenseCategoryId(), natures)
                    != ExpenseNature.ONE_OFF) continue;
            oneOff = oneOff.add(nz(row.amountBase()));
            oneOffRows += row.itemCount();
        }

        BigDecimal normal = total.subtract(oneOff)
                .divide(BigDecimal.valueOf(ids.size()), 2, RoundingMode.HALF_EVEN);
        return Optional.of(new Normal(ids, average, normal, oneOff, oneOffRows));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FR-630/631/634 · 三分(刚性 / 弹性 / 一次性)
    // ══════════════════════════════════════════════════════════════════════════

    /** 三分里的一段 */
    public record SplitPart(ExpenseNature nature, BigDecimal amountBase, BigDecimal pct,
                            int itemCount, String color, String ink, boolean hatched) {}

    /**
     * 三分结果。
     *
     * @param unclassifiedCount 未分类的笔数 —— FR-636 要求<b>显式说出来</b>,
     *                          它们算在弹性里,但必须让用户知道有这么多笔还没归类
     * @param rigidPct          FR-634 · 刚性占比,单独作为一个指标呈现
     */
    public record Split(List<SplitPart> parts, BigDecimal totalBase,
                        int unclassifiedCount, BigDecimal rigidPct) {

        /**
         * FR-635 · 数据够不够渲染这一块。
         *
         * <p>90% 的笔都未分类时<b>不渲染</b> —— 一个「未分类 100%」的三分块
         * 没有任何信息量,只会让用户以为功能坏了。</p>
         */
        public boolean renderable(int totalRows) {
            return totalRows > 0 && unclassifiedCount * 10 < totalRows * 9;
        }
    }

    /** FR-630 · 一个窗口内的三分。窗口为空 / 开关关着 → empty。 */
    public Optional<Split> split(long familyId, List<Long> periodIds) {
        if (!enabled(familyId) || periodIds == null || periodIds.isEmpty()) return Optional.empty();

        Map<Long, ExpenseNature> natures = natureService.natureMap(familyId);
        Map<ExpenseNature, BigDecimal> amt = new java.util.EnumMap<>(ExpenseNature.class);
        Map<ExpenseNature, Integer> cnt = new java.util.EnumMap<>(ExpenseNature.class);
        BigDecimal total = BigDecimal.ZERO;
        int unclassified = 0;

        for (var r : cashFlowMapper.sumExpenseByPeriodCategoryOneOff(familyId, periodIds)) {
            if (!"consumption".equals(r.categoryCode())) continue;
            ExpenseNature n = natureService.natureOf(r.oneOff(), r.expenseCategoryId(), natures);
            BigDecimal v = nz(r.amountBase());
            amt.merge(n, v, BigDecimal::add);
            cnt.merge(n, r.itemCount(), Integer::sum);
            total = total.add(v);
            // 未分类的笔算弹性(FR-636),但要单独数出来告诉用户
            if (r.expenseCategoryId() == null && !r.oneOff()) unclassified += r.itemCount();
        }
        if (total.signum() == 0) return Optional.empty();

        List<SplitPart> parts = new ArrayList<>();
        // 固定顺序:刚性 → 弹性 → 一次性。不按金额排 —— 这是一根【轴】,
        // 顺序本身就是信息(压不动 → 压得动 → 离轴),按金额排会让它每个月换位置。
        for (ExpenseNature n : List.of(ExpenseNature.RIGID, ExpenseNature.FLEX, ExpenseNature.ONE_OFF)) {
            BigDecimal v = amt.getOrDefault(n, BigDecimal.ZERO);
            if (v.signum() == 0 && cnt.getOrDefault(n, 0) == 0) continue;
            parts.add(new SplitPart(n, v, pct(v, total), cnt.getOrDefault(n, 0),
                    ExpensePalette.of(n), ExpensePalette.inkOn(n), ExpensePalette.hatched(n)));
        }
        return Optional.of(new Split(parts, total, unclassified,
                pct(amt.getOrDefault(ExpenseNature.RIGID, BigDecimal.ZERO), total)));
    }

    /** FR-631 · 12 期的刚性/弹性趋势。刚性线一旦抬升就是生活成本真的上了台阶。 */
    public record SeriesPoint(Long periodId, LocalDate periodStart,
                              BigDecimal rigid, BigDecimal flex, BigDecimal oneOff) {}

    public List<SeriesPoint> series(long familyId) {
        if (!enabled(familyId)) return List.of();
        List<ExpenseLedgerService.PeriodExpense> window = ledgerService.recentClosed(familyId, WINDOW);
        if (window.isEmpty()) return List.of();

        Map<Long, LocalDate> startOf = new java.util.LinkedHashMap<>();
        for (var pe : window) startOf.put(pe.periodId(), pe.periodStart());
        List<Long> ids = new ArrayList<>(startOf.keySet());

        Map<Long, ExpenseNature> natures = natureService.natureMap(familyId);
        Map<Long, BigDecimal[]> acc = new java.util.LinkedHashMap<>();
        for (Long id : ids) acc.put(id, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});

        for (var r : cashFlowMapper.sumExpenseByPeriodCategoryOneOff(familyId, ids)) {
            if (!"consumption".equals(r.categoryCode())) continue;
            BigDecimal[] a = acc.get(r.periodId());
            if (a == null) continue;
            int i = switch (natureService.natureOf(r.oneOff(), r.expenseCategoryId(), natures)) {
                case RIGID -> 0;
                case FLEX -> 1;
                case ONE_OFF -> 2;
            };
            a[i] = a[i].add(nz(r.amountBase()));
        }
        List<SeriesPoint> out = new ArrayList<>();
        // 时间升序 —— recentClosed 是倒序的,趋势图要从左到右读
        List<Long> asc = new ArrayList<>(ids);
        java.util.Collections.reverse(asc);
        for (Long id : asc) {
            BigDecimal[] a = acc.get(id);
            out.add(new SeriesPoint(id, startOf.get(id), a[0], a[1], a[2]));
        }
        return out;
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) return BigDecimal.ZERO;
        return part.multiply(BigDecimal.valueOf(100))
                .divide(whole.abs(), 1, RoundingMode.HALF_UP);
    }

    /** FR-633 · 一次性明细里的一笔 */
    public record OneOffRow(LocalDate occurredAt, String categoryName, BigDecimal amount,
                            boolean fromCategory) {
        /** 「是类目性质还是逐笔勾」—— 用户要看得出这笔为什么被剔除 */
        public String reason() { return fromCategory ? "类目就是一次性" : "这笔勾了一次性"; }
    }

    /**
     * FR-633 · 点「剔除了 N 笔」展开的那 N 笔。
     *
     * <p>只在用户真的点开时才查 —— 报表首屏不带这份明细。</p>
     */
    public List<OneOffRow> oneOffDetail(long familyId, List<Long> periodIds) {
        if (!enabled(familyId) || periodIds == null || periodIds.isEmpty()) return List.of();
        Map<Long, ExpenseNature> natures = natureService.natureMap(familyId);
        List<OneOffRow> out = new ArrayList<>();
        for (Long pid : periodIds) {
            for (var f : expenseFlowMapper.drillDownAll(familyId, pid)) {
                ExpenseNature n = natureService.natureOf(f.oneOff(), f.categoryId(), natures);
                if (n != ExpenseNature.ONE_OFF) continue;
                out.add(new OneOffRow(f.occurredAt(), f.categoryName(), f.amount(), !f.oneOff()));
            }
        }
        out.sort((a, b) -> nz(b.amount()).compareTo(nz(a.amount())));
        return out;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
