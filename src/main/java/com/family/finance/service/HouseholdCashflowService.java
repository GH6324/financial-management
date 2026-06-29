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
import java.util.Objects;

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

    public BigDecimal avgMonthlyExpense(long familyId) {
        BigDecimal preferred = avgFromMemberCashflow(familyId, true);
        if (preferred != null) return preferred;
        return avgFromCashFlow(familyId, false);
    }

    public BigDecimal avgMonthlyIncome(long familyId) {
        BigDecimal preferred = avgFromMemberCashflow(familyId, false);
        if (preferred != null) return preferred;
        return avgFromCashFlow(familyId, true);
    }

    public BigDecimal currentSavingsRate(long familyId) {
        List<FamilyPeriodAggregate> recent = cashflowMapper.findFamilyAggregateRecent(familyId, 1);
        if (!recent.isEmpty()) {
            FamilyPeriodAggregate a = recent.get(0);
            if (a.totalIncome() != null && a.totalIncome().signum() > 0) {
                BigDecimal expense = a.totalExpense() == null ? BigDecimal.ZERO : a.totalExpense();
                return a.totalIncome().subtract(expense)
                    .divide(a.totalIncome(), 6, RoundingMode.HALF_EVEN);
            }
        }
        return factViewService.savingsRate(factViewService.loadDefault(familyId));
    }

    public BigDecimal medianMonthlySavings(long familyId) {
        List<FamilyPeriodAggregate> recent = cashflowMapper.findFamilyAggregateRecent(familyId, 6);
        if (recent.isEmpty()) {
            return avgMonthlyIncome(familyId).subtract(avgMonthlyExpense(familyId));
        }
        List<BigDecimal> savings = recent.stream()
            .map(a -> a.totalIncome().subtract(a.totalExpense()))
            .sorted().toList();
        int n = savings.size();
        if (n % 2 == 1) return savings.get(n / 2);
        return savings.get(n / 2 - 1).add(savings.get(n / 2))
            .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_EVEN);
    }

    public int[] filledMonthRatio(long familyId) {
        List<FamilyPeriodAggregate> recent = cashflowMapper.findFamilyAggregateRecent(familyId, LOOKBACK_PERIODS);
        return new int[]{recent.size(), LOOKBACK_PERIODS};
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
