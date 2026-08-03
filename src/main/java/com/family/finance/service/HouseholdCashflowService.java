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

    private BigDecimal avgFromMemberCashflow(long familyId, boolean expense) {
        List<FamilyPeriodAggregate> recent = cashflowMapper.findFamilyAggregateRecent(familyId, LOOKBACK_PERIODS);
        if (recent.isEmpty()) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (FamilyPeriodAggregate a : recent) {
            BigDecimal v = expense ? a.totalExpense() : a.totalIncome();
            if (v != null) sum = sum.add(v);
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
