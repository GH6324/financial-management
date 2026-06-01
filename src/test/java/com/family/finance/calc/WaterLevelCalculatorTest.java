package com.family.finance.calc;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** v0.5 FR-72/73 · 财富水位计算防回归。 */
class WaterLevelCalculatorTest {

    private static List<BigDecimal> of(double... vs) {
        return java.util.Arrays.stream(vs).mapToObj(BigDecimal::valueOf).toList();
    }

    @Test
    void cumulativeFactorCompoundsAnnual() {
        // 两年各 2%(yearsPerStep=1)→ 1.02 × 1.02 = 1.0404
        assertThat(WaterLevelCalculator.cumulativeFactor(of(2, 2), 1.0).doubleValue())
                .isCloseTo(1.0404, within(0.0001));
    }

    @Test
    void cumulativeFactorMonthlyProRated() {
        // 12 个月、年化 12% → ≈ 1.12(月因子 1.12^(1/12) 累乘 12 次)
        var monthly = of(12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12, 12);
        assertThat(WaterLevelCalculator.cumulativeFactor(monthly, 1.0 / 12.0).doubleValue())
                .isCloseTo(1.12, within(0.001));
    }

    @Test
    void baselineValueMultipliesAnchor() {
        assertThat(WaterLevelCalculator.baselineValue(new BigDecimal("2820000"), new BigDecimal("1.0404")))
                .isEqualByComparingTo("2933928.00");
    }

    @Test
    void realReturnStripsBenchmark() {
        // 名义 10% vs 基准 2% → (1.1/1.02 − 1) ≈ 7.84%
        assertThat(WaterLevelCalculator.realReturnPct(new BigDecimal("10"), new BigDecimal("2")).doubleValue())
                .isCloseTo(7.84, within(0.02));
    }

    @Test
    void realReturnNegativeWhenBeatenByBenchmark() {
        // 名义 5% vs M2 9% → 相对社会收益为负
        assertThat(WaterLevelCalculator.realReturnPct(new BigDecimal("5"), new BigDecimal("9")).doubleValue())
                .isLessThan(0.0);
    }

    @Test
    void factorToCumulativePct() {
        assertThat(WaterLevelCalculator.factorToCumulativePct(new BigDecimal("1.0404")).doubleValue())
                .isCloseTo(4.04, within(0.01));
    }
}
