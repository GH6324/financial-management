package com.family.finance.service.goal;

import com.family.finance.domain.goal.GoalComparator;
import com.family.finance.domain.goal.GoalMetric;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v0.16 · pace(进度 vs 时间)判定护栏。today 入参,不依赖真实时钟。
 */
class GoalPaceCalculatorTest {

    private static final LocalDate CREATED = LocalDate.of(2026, 1, 1);
    private static final LocalDate DEADLINE = LocalDate.of(2026, 12, 31); // 约 364 天
    private static final LocalDate MID = LocalDate.of(2026, 7, 1);        // 约 50% 时间

    private static GoalPaceCalculator.Pace amount(double cur, double tgt) {
        return GoalPaceCalculator.compute(GoalMetric.AMOUNT_TOTAL, GoalComparator.GTE,
                BigDecimal.valueOf(cur), BigDecimal.valueOf(tgt), null, CREATED, DEADLINE, MID);
    }

    @Test
    void ahead_when_attain_leads_time() {
        var p = amount(150_000, 200_000); // 75% > 时间 ~50%
        assertThat(p.status()).isEqualTo(GoalPaceCalculator.Status.AHEAD);
        assertThat(p.daysLeft()).isPositive();
    }

    @Test
    void behind_when_attain_lags_time_over_threshold() {
        var p = amount(30_000, 200_000); // 15% vs 时间 ~50% → 差 >15pp
        assertThat(p.status()).isEqualTo(GoalPaceCalculator.Status.BEHIND);
    }

    @Test
    void achieved_when_reached() {
        var p = amount(250_000, 200_000);
        assertThat(p.status()).isEqualTo(GoalPaceCalculator.Status.ACHIEVED);
    }

    @Test
    void rate_metric_deadline_no_pace_judgement() {
        var p = GoalPaceCalculator.compute(GoalMetric.RETURN_XIRR, GoalComparator.GTE,
                BigDecimal.valueOf(5), BigDecimal.valueOf(20), null, CREATED, DEADLINE, MID);
        assertThat(p.status()).isEqualTo(GoalPaceCalculator.Status.NONE);
        assertThat(p.daysLeft()).isPositive(); // 仍给倒计时
    }

    @Test
    void open_goal_has_no_deadline_no_status() {
        var p = GoalPaceCalculator.compute(GoalMetric.AMOUNT_TOTAL, GoalComparator.GTE,
                BigDecimal.valueOf(50), BigDecimal.valueOf(100), null, CREATED, null, MID);
        assertThat(p.status()).isEqualTo(GoalPaceCalculator.Status.NONE);
        assertThat(p.daysLeft()).isNull();
        assertThat(p.timePct()).isNull();
    }

    @Test
    void lte_liability_uses_baseline_reverse() {
        // 起点 100k,目标 ≤50k,当前 80k → (100-80)/(100-50)=0.4
        var p = GoalPaceCalculator.compute(GoalMetric.TOTAL_LIAB, GoalComparator.LTE,
                BigDecimal.valueOf(80_000), BigDecimal.valueOf(50_000), BigDecimal.valueOf(100_000),
                CREATED, DEADLINE, MID);
        assertThat(p.attainPct()).isEqualByComparingTo("0.4");
    }

    @Test
    void lte_achieved_when_below_target() {
        var p = GoalPaceCalculator.compute(GoalMetric.TOTAL_LIAB, GoalComparator.LTE,
                BigDecimal.valueOf(40_000), BigDecimal.valueOf(50_000), BigDecimal.valueOf(100_000),
                CREATED, DEADLINE, MID);
        assertThat(p.status()).isEqualTo(GoalPaceCalculator.Status.ACHIEVED);
    }
}
