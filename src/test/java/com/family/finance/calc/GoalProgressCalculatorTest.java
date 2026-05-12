package com.family.finance.calc;

import com.family.finance.domain.goal.GoalParams;
import com.family.finance.domain.goal.GoalType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Year;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

/**
 * GoalProgressCalculator 单测 · v0.3 FR-50。
 */
class GoalProgressCalculatorTest {

    // ---------- RETIREMENT ----------

    @Test
    void retirementTargetUsesInflatedAnnualOverWithdrawalRate() {
        var p = GoalParams.builder()
                .currentAge(38).retireAge(60)
                .monthlyExpense(new BigDecimal("15000"))
                .inflationRate(new BigDecimal("0.025"))
                .withdrawalRate(new BigDecimal("0.04"))
                .build();
        BigDecimal target = GoalProgressCalculator.computeRetirementTarget(p);
        // 15000 × 12 × 1.025^22 / 0.04 · 1.025^22 ≈ 1.7211
        // → 180000 × 1.7211 / 0.04 ≈ 7,745,000(允许 ±0.1% 浮点误差)
        assertThat(target.doubleValue()).isCloseTo(7_745_000d, offset(5000d));
    }

    @Test
    void retirementTargetDefaultsInflationAndWithdrawal() {
        var p = GoalParams.builder()
                .currentAge(40).retireAge(60)
                .monthlyExpense(new BigDecimal("10000"))
                .build();
        BigDecimal target = GoalProgressCalculator.computeRetirementTarget(p);
        // 10000 × 12 × 1.025^20 / 0.04 ≈ 4,917,500
        assertThat(target.doubleValue()).isGreaterThan(4_000_000d);
    }

    @Test
    void retirementMissingRequiredThrows() {
        var p = GoalParams.builder().monthlyExpense(new BigDecimal("10000")).build();
        assertThatThrownBy(() -> GoalProgressCalculator.computeRetirementTarget(p))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- EDUCATION ----------

    @Test
    void educationTargetUsesInflationOverTargetYearOffset() {
        int currentYear = Year.now().getValue();
        int birthYear = currentYear - 6; // 孩子 6 岁 · 12 年后上大学
        var p = GoalParams.builder()
                .childBirthYear(birthYear)
                .targetYearOffset(18)
                .targetAmount(new BigDecimal("800000"))
                .inflationRate(new BigDecimal("0.03"))
                .build();
        BigDecimal target = GoalProgressCalculator.computeEducationTarget(p);
        // 800000 × 1.03^12 ≈ 1,140,580
        assertThat(target.doubleValue()).isGreaterThan(1_100_000d);
        assertThat(target.doubleValue()).isLessThan(1_200_000d);
    }

    // ---------- EMERGENCY ----------

    @Test
    void emergencyTargetUsesAutoBaselineWhenProvided() {
        var p = GoalParams.builder()
                .monthsTarget(6)
                .autoBaseline(true)
                .build();
        BigDecimal target = GoalProgressCalculator.computeEmergencyTarget(p, new BigDecimal("18000"));
        assertThat(target).isEqualByComparingTo(new BigDecimal("108000.00"));
    }

    @Test
    void emergencyTargetUsesFixedBaselineWhenAutoFalse() {
        var p = GoalParams.builder()
                .monthsTarget(12)
                .autoBaseline(false)
                .fixedBaseline(new BigDecimal("20000"))
                .build();
        BigDecimal target = GoalProgressCalculator.computeEmergencyTarget(p, null);
        assertThat(target).isEqualByComparingTo(new BigDecimal("240000.00"));
    }

    @Test
    void emergencyTargetZeroWhenNoBaselineAvailable() {
        var p = GoalParams.builder().monthsTarget(6).autoBaseline(true).build();
        BigDecimal target = GoalProgressCalculator.computeEmergencyTarget(p, null);
        assertThat(target).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ---------- progress / median ----------

    @Test
    void progressIsZeroWhenTargetIsZero() {
        assertThat(GoalProgressCalculator.computeProgress(new BigDecimal("1000"), BigDecimal.ZERO))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void progressFifthyFivePercent() {
        BigDecimal p = GoalProgressCalculator.computeProgress(new BigDecimal("550"), new BigDecimal("1000"));
        assertThat(p.doubleValue()).isCloseTo(0.55d, offset(1e-4));
    }

    @Test
    void medianOddCount() {
        var list = List.of(new BigDecimal("8000"), new BigDecimal("17500"), new BigDecimal("22000"));
        assertThat(GoalProgressCalculator.medianMonthlyContribution(list))
                .isEqualByComparingTo(new BigDecimal("17500"));
    }

    @Test
    void medianEvenCount() {
        var list = List.of(new BigDecimal("10000"), new BigDecimal("20000"));
        assertThat(GoalProgressCalculator.medianMonthlyContribution(list))
                .isEqualByComparingTo(new BigDecimal("15000.00"));
    }

    @Test
    void medianEmptyReturnsZero() {
        assertThat(GoalProgressCalculator.medianMonthlyContribution(List.of()))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void computeTargetDispatchesByType() {
        var emerg = GoalParams.builder().monthsTarget(6).autoBaseline(true).build();
        assertThat(GoalProgressCalculator.computeTarget(GoalType.EMERGENCY, emerg, new BigDecimal("18000")))
                .isEqualByComparingTo(new BigDecimal("108000.00"));
    }
}
