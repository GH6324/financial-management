package com.family.finance.calc;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * GoalProjector 单测 · v0.3 决策 22 三情景。
 */
class GoalProjectorTest {

    @Test
    void futureValuePureCompoundingNoContribution() {
        // 100w × 1.05^10 ≈ 1,628,895
        double fv = GoalProjector.futureValue(1_000_000d, 0d, 0.05d, 10d);
        assertThat(fv).isCloseTo(1_628_895d, offset(100d));
    }

    @Test
    void futureValueAnnuityOnlyAtFivePercent() {
        // PV=0, m=10k/月, r=5% 年化(月度复利 mr=0.05/12), n=10 年
        // (1+mr)^120 ≈ 1.6470 · annuity FV ≈ 1,552,823
        double fv = GoalProjector.futureValue(0d, 10_000d, 0.05d, 10d);
        assertThat(fv).isCloseTo(1_552_823d, offset(500d));
    }

    @Test
    void futureValueWithPvAndAnnuity() {
        // PV=100w × 1.05^10 ≈ 1,628,895 · + annuity 1,552,823 ≈ 3,181,718
        double fv = GoalProjector.futureValue(1_000_000d, 10_000d, 0.05d, 10d);
        assertThat(fv).isCloseTo(3_181_718d, offset(500d));
    }

    @Test
    void scenarioPathLengthIsYearsPlusOne() {
        var path = GoalProjector.scenarioPath(
                new BigDecimal("1000000"), new BigDecimal("10000"),
                GoalProjector.R_NEUTRAL, 30);
        assertThat(path).hasSize(31);
        assertThat(path.get(0).doubleValue()).isCloseTo(1_000_000d, offset(1d));
        // 30 年后(中性 5%)远超 PV
        assertThat(path.getLast().doubleValue()).isGreaterThan(5_000_000d);
    }

    @Test
    void scenarioPathOptimisticOutperformsPessimistic() {
        var opt = GoalProjector.scenarioPath(
                new BigDecimal("100000"), new BigDecimal("5000"),
                GoalProjector.R_OPTIMISTIC, 20);
        var pes = GoalProjector.scenarioPath(
                new BigDecimal("100000"), new BigDecimal("5000"),
                GoalProjector.R_PESSIMISTIC, 20);
        assertThat(opt.getLast().doubleValue()).isGreaterThan(pes.getLast().doubleValue());
    }

    @Test
    void achievementDateReturnsTodayWhenAlreadyMet() {
        LocalDate date = GoalProjector.achievementDate(
                new BigDecimal("2000000"), BigDecimal.ZERO, new BigDecimal("1000000"),
                GoalProjector.R_NEUTRAL);
        assertThat(date).isEqualTo(LocalDate.now());
    }

    @Test
    void achievementDateReachesTargetInFinitYears() {
        // PV=100w, m=10k/月, r=5% · 解 fv >= 200w · 大约 6-7 年
        LocalDate date = GoalProjector.achievementDate(
                new BigDecimal("1000000"), new BigDecimal("10000"),
                new BigDecimal("2000000"), GoalProjector.R_NEUTRAL);
        long days = date.toEpochDay() - LocalDate.now().toEpochDay();
        // 6 年 ≈ 2190 天 · 7 年 ≈ 2555
        assertThat(days).isBetween(1500L, 3000L);
    }

    @Test
    void achievementDateNullWhenTargetUnreachableInFiftyYears() {
        // PV=10k, m=0, r=2% · 50 年内永远到不了 1 亿
        LocalDate date = GoalProjector.achievementDate(
                new BigDecimal("10000"), BigDecimal.ZERO,
                new BigDecimal("100000000"), GoalProjector.R_PESSIMISTIC);
        assertThat(date).isNull();
    }

    @Test
    void projectReturnsAllThreeScenarios() {
        var r = GoalProjector.project(
                new BigDecimal("1140000"), new BigDecimal("17500"),
                new BigDecimal("2000000"), 30);
        assertThat(r.optimisticPath()).hasSize(31);
        assertThat(r.neutralPath()).hasSize(31);
        assertThat(r.pessimisticPath()).hasSize(31);
        // 乐观先到达 · 悲观最晚到达
        assertThat(r.optimisticDate()).isBeforeOrEqualTo(r.neutralDate());
        assertThat(r.neutralDate()).isBeforeOrEqualTo(r.pessimisticDate());
    }

    @Test
    void projectHandlesNullInputsSafely() {
        var r = GoalProjector.project(null, null, new BigDecimal("100000"), 10);
        assertThat(r.optimisticPath().get(0).doubleValue()).isCloseTo(0d, offset(0.01));
    }
}
