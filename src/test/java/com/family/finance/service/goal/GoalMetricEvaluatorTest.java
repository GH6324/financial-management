package com.family.finance.service.goal;

import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.goal.GoalMetric;
import com.family.finance.factview.AccountPerformance;
import com.family.finance.factview.KpiSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v0.16 · 目标指标聚合口径护栏:金额 Σ、比率价值加权、0 账户走全家、空集安全。
 */
class GoalMetricEvaluatorTest {

    private static AccountPerformance acc(long id, AccountType type, double value, Double xirr) {
        return AccountPerformance.basic(id, "acc" + id, type, "CNY",
                BigDecimal.valueOf(value), xirr == null ? null : BigDecimal.valueOf(xirr), List.of());
    }

    private static KpiSnapshot kpi(double netWorth, double totalAssets, double totalLiab) {
        return new KpiSnapshot(BigDecimal.valueOf(netWorth), BigDecimal.valueOf(totalAssets),
                BigDecimal.valueOf(totalLiab), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private final List<AccountPerformance> perf = List.of(
            acc(1, AccountType.CASH, 100, 0.10),
            acc(2, AccountType.STOCK, 300, 0.20),
            acc(3, AccountType.STOCK, 600, null));

    @Test
    void amount_total_sums_selected_accounts() {
        BigDecimal v = GoalMetricEvaluator.aggregate(GoalMetric.AMOUNT_TOTAL, Set.of(1L, 2L),
                perf, kpi(1000, 1000, 0), BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(v).isEqualByComparingTo("400");
    }

    @Test
    void amount_total_zero_accounts_uses_family_net_worth() {
        BigDecimal v = GoalMetricEvaluator.aggregate(GoalMetric.AMOUNT_TOTAL, Set.of(),
                perf, kpi(1000, 1000, 0), BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(v).isEqualByComparingTo("1000");
    }

    @Test
    void return_xirr_is_value_weighted_and_pct() {
        // (0.10*100 + 0.20*300) / (100+300) = 0.175 → 17.50%
        BigDecimal v = GoalMetricEvaluator.aggregate(GoalMetric.RETURN_XIRR, Set.of(1L, 2L),
                perf, kpi(1000, 1000, 0), BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(v).isEqualByComparingTo("17.50");
    }

    @Test
    void return_xirr_zero_accounts_uses_family_twr_pct() {
        BigDecimal v = GoalMetricEvaluator.aggregate(GoalMetric.RETURN_XIRR, null,
                perf, kpi(1000, 1000, 0), BigDecimal.ZERO, new BigDecimal("12.30"));
        assertThat(v).isEqualByComparingTo("12.30");
    }

    @Test
    void share_pct_is_sum_over_net_worth() {
        // (100+300)/1000 = 40%
        BigDecimal v = GoalMetricEvaluator.aggregate(GoalMetric.SHARE_PCT, Set.of(1L, 2L),
                perf, kpi(1000, 1000, 0), BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(v).isEqualByComparingTo("40.00");
    }

    @Test
    void cash_total_selected_only_cash_accounts() {
        BigDecimal v = GoalMetricEvaluator.aggregate(GoalMetric.CASH_TOTAL, Set.of(1L, 2L),
                perf, kpi(1000, 1000, 0), BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(v).isEqualByComparingTo("100"); // 只有账户1是 CASH
    }

    @Test
    void empty_perf_is_zero_not_crash() {
        BigDecimal v = GoalMetricEvaluator.aggregate(GoalMetric.AMOUNT_TOTAL, Set.of(9L),
                List.of(), kpi(0, 0, 0), BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(v).isEqualByComparingTo("0");
    }
}
