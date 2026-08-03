package com.family.finance.service;

import com.family.finance.factview.AccountPeriodFact;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.repository.PeriodMemberCashflowMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper.FamilyPeriodAggregate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 家庭月度收支指标 · v0.3 FR-51(2026-05-13 修订为成员级)。
 *
 * <p>数据源:{@code period_member_cashflow} 表 by 成员填报 ·
 * 家庭总额 = SUM(各成员) · 跨成员聚合后按"近 N 期均值/中位"算指标。</p>
 *
 * <p>fallback: v0.2 cash_flow 表(account-level INCOME / EXPENSE)。</p>
 */
@Service
@RequiredArgsConstructor
public class HouseholdCashflowService {

    private static final int LOOKBACK_PERIODS = 12;

    private final PeriodMemberCashflowMapper cashflowMapper;
    private final FactViewService factViewService;
    /** v1.8 · 家庭支出唯一口径入口(逐笔 > 总额)· 见 ExpenseLedgerService 类注释 */
    private final com.family.finance.service.expense.ExpenseLedgerService expenseLedger;

    /** v1.8 · 走统一口径(逐笔 > 总额);都没有时才回落 cash_flow 汇总。 */
    public BigDecimal avgMonthlyExpense(long familyId) {
        var recent = expenseLedger.recent(familyId, LOOKBACK_PERIODS);
        if (!recent.isEmpty()) {
            BigDecimal sum = BigDecimal.ZERO;
            for (var pe : recent) sum = sum.add(pe.amountBase());
            return sum.divide(BigDecimal.valueOf(recent.size()), 2, RoundingMode.HALF_EVEN);
        }
        return avgFromCashFlow(familyId, false);
    }

    // v0.12 FR-142 · 收入侧口径:每期收入 = PMC 手填收入(历史)优先,否则该期 cash_flow INCOME 汇总
    //   (新账期由收入侧录入 → cash_flow)。支出侧不变(2框仍填总支出)。避免新账期收入被 PMC 空值低估 + 防 NPE。
    public BigDecimal avgMonthlyIncome(long familyId) {
        List<FamilyPeriodAggregate> recent = cashflowMapper.findFamilyAggregateRecent(familyId, LOOKBACK_PERIODS);
        if (recent.isEmpty()) return avgFromCashFlow(familyId, true);
        Map<Long, BigDecimal> cashInc = cashIncomeByPeriod(familyId);
        BigDecimal sum = BigDecimal.ZERO;
        for (FamilyPeriodAggregate a : recent) sum = sum.add(incomeBlend(a, cashInc));
        return sum.divide(BigDecimal.valueOf(recent.size()), 2, RoundingMode.HALF_EVEN);
    }

    public BigDecimal currentSavingsRate(long familyId) {
        List<FamilyPeriodAggregate> recent = cashflowMapper.findFamilyAggregateRecent(familyId, 1);
        if (!recent.isEmpty()) {
            FamilyPeriodAggregate a = recent.get(0);
            BigDecimal income = incomeBlend(a, cashIncomeByPeriod(familyId));
            if (income.signum() > 0) {
                // v1.8 · 支出改走统一口径(逐笔 > 总额);收入侧口径不动
                BigDecimal expense = expenseLedger.byPeriod(familyId, a.periodId()).amountBase();
                return income.subtract(expense).divide(income, 6, RoundingMode.HALF_EVEN);
            }
        }
        return factViewService.savingsRate(factViewService.loadDefault(familyId));
    }

    public BigDecimal medianMonthlySavings(long familyId) {
        List<FamilyPeriodAggregate> recent = cashflowMapper.findFamilyAggregateRecent(familyId, 6);
        if (recent.isEmpty()) {
            return avgMonthlyIncome(familyId).subtract(avgMonthlyExpense(familyId));
        }
        Map<Long, BigDecimal> cashInc = cashIncomeByPeriod(familyId);
        // v1.8 · 支出改走统一口径(逐笔 > 总额);收入仍用 incomeBlend
        var expByPeriod = expenseLedger.byPeriods(familyId,
                recent.stream().map(FamilyPeriodAggregate::periodId).toList());
        List<BigDecimal> savings = recent.stream()
            .map(a -> incomeBlend(a, cashInc).subtract(
                    expByPeriod.containsKey(a.periodId())
                        ? expByPeriod.get(a.periodId()).amountBase() : BigDecimal.ZERO))
            .sorted().toList();
        int n = savings.size();
        if (n % 2 == 1) return savings.get(n / 2);
        return savings.get(n / 2 - 1).add(savings.get(n / 2))
            .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_EVEN);
    }

    /** v0.12 · 单期收入:PMC 手填收入>0 用之(历史),否则用该期 cash_flow INCOME 汇总(新账期收入侧录入)。 */
    /**
     * v1.8 · 储蓄能力折线的数据源:近 N 期(升序)的收入 / 支出,**各按自己的口径**取。
     *
     * <p>为什么要有这个方法:折线原来直接铺 PMC 聚合,于是出现两个毛病 ——</p>
     * <ul>
     *   <li>老毛病:家庭只逐笔录收入、没填过 PMC 时,折线整条空白(beta 一直如此),
     *       而同一段的 KPI「月均收入」却有值(它会回落 cash_flow)。同屏自相矛盾。</li>
     *   <li>v1.8 新增的毛病:{@code filledMonthRatio} 改判「统一口径有值」后,
     *       逐笔家庭会被判成「有数据」→ 渲染 KPI 卡,而折线仍取 PMC → 图区空白。</li>
     * </ul>
     *
     * <p>所以期集合取「PMC 期 ∪ 统一口径有支出的期」,收入用 incomeBlend(PMC 优先否则 cash_flow),
     * 支出用 {@link com.family.finance.service.expense.ExpenseLedgerService}。没数据的点留 null → 灰柱。</p>
     */
    public List<SeriesPoint> recentSeries(long familyId, int limit) {
        var pmc = cashflowMapper.findFamilyAggregateRecent(familyId, limit);
        Map<Long, FamilyPeriodAggregate> pmcById = new java.util.LinkedHashMap<>();
        Map<Long, java.time.LocalDate> startById = new java.util.LinkedHashMap<>();
        for (var a : pmc) {
            pmcById.put(a.periodId(), a);
            startById.put(a.periodId(), a.periodStart());
        }
        // 统一口径有支出、但 PMC 里没有的期(逐笔家庭的常态)
        for (var pe : expenseLedger.recent(familyId, limit)) {
            startById.putIfAbsent(pe.periodId(), pe.periodStart());
        }
        if (startById.isEmpty()) return List.of();

        var expByPeriod = expenseLedger.byPeriods(familyId, new java.util.ArrayList<>(startById.keySet()));
        Map<Long, BigDecimal> cashInc = cashIncomeByPeriod(familyId);

        List<SeriesPoint> out = new java.util.ArrayList<>();
        for (var e : startById.entrySet()) {
            Long pid = e.getKey();
            var agg = pmcById.get(pid);
            BigDecimal income = agg != null ? incomeBlend(agg, cashInc) : cashInc.get(pid);
            var pe = expByPeriod.get(pid);
            BigDecimal expense = (pe != null && pe.filled()) ? pe.amountBase() : null;
            out.add(new SeriesPoint(pid, e.getValue(), income, expense));
        }
        // 期起始升序(缺日期的排在前面,用 id 兜底)
        out.sort((x, y) -> {
            if (x.periodStart() == null && y.periodStart() == null) return Long.compare(x.periodId(), y.periodId());
            if (x.periodStart() == null) return -1;
            if (y.periodStart() == null) return 1;
            return x.periodStart().compareTo(y.periodStart());
        });
        if (out.size() > limit) out = out.subList(out.size() - limit, out.size());
        return out;
    }

    /** 折线上的一个点。income / expense 可为 null(该月没数据 → 灰柱)。 */
    public record SeriesPoint(Long periodId, java.time.LocalDate periodStart,
                              BigDecimal income, BigDecimal expense) {}

    private BigDecimal incomeBlend(FamilyPeriodAggregate a, Map<Long, BigDecimal> cashIncomeByPeriod) {
        if (a.totalIncome() != null && a.totalIncome().signum() > 0) return a.totalIncome();
        return cashIncomeByPeriod.getOrDefault(a.periodId(), BigDecimal.ZERO);
    }

    /** 各期 cash_flow INCOME 汇总(本位币口径 · loadDefault)· 供收入侧回退。 */
    private Map<Long, BigDecimal> cashIncomeByPeriod(long familyId) {
        FactSlice slice = factViewService.loadDefault(familyId);
        return slice.rows().stream()
            .filter(r -> r.periodId() != null && r.incomeBase() != null)
            .collect(Collectors.groupingBy(AccountPeriodFact::periodId,
                Collectors.reducing(BigDecimal.ZERO, AccountPeriodFact::incomeBase, BigDecimal::add)));
    }

    /**
     * v1.8 · 判据从「PMC 有行」改成「统一口径 source != NONE」。
     * 否则只录了逐笔、没填过总额的月份会被判成「没填」,导致已填月数偏少、月储蓄中位数取样变小。
     */
    public int[] filledMonthRatio(long familyId) {
        return new int[]{expenseLedger.recent(familyId, LOOKBACK_PERIODS).size(), LOOKBACK_PERIODS};
    }

    public List<FamilyPeriodAggregate> findRecentAggregates(long familyId, int limit) {
        return cashflowMapper.findFamilyAggregateRecent(familyId, limit);
    }

    /** v0.10 · 指定期已填收支的成员数(PMC 成员级 · 给「人赚 vs 钱赚」卡完整度用)。periodId 空 → 0。 */
    public int filledMembersForPeriod(Long periodId) {
        if (periodId == null) return 0;
        return cashflowMapper.findFamilyAggregateForPeriod(periodId)
                .map(a -> a.filledMembers() == null ? 0 : a.filledMembers())
                .orElse(0);
    }

    /**
     * v1.8 · 收窄成只算收入。原来有个 {@code expense} 开关,但支出侧已全部改走
     * {@link com.family.finance.service.expense.ExpenseLedgerService},那个分支成了死路 ——
     * 留着会让下一个人以为「支出也可以从这儿读」,正是口径散落的起点。
     */
    private BigDecimal avgIncomeFromMemberCashflow(long familyId) {
        List<FamilyPeriodAggregate> recent = cashflowMapper.findFamilyAggregateRecent(familyId, LOOKBACK_PERIODS);
        if (recent.isEmpty()) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (FamilyPeriodAggregate a : recent) {
            if (a.totalIncome() != null) sum = sum.add(a.totalIncome());
        }
        return sum.divide(BigDecimal.valueOf(recent.size()), 2, RoundingMode.HALF_EVEN);
    }

    private BigDecimal avgFromCashFlow(long familyId, boolean income) {
        FactSlice slice = factViewService.loadDefault(familyId);
        if (slice.periodIds().isEmpty()) return BigDecimal.ZERO;
        BigDecimal total = slice.rows().stream()
            .map(income ? AccountPeriodFact::incomeBase : AccountPeriodFact::expenseBase)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        int n = Math.max(1, slice.periodIds().size());
        return total.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_EVEN);
    }
}
