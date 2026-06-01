package com.family.finance.calc;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** v0.5 FR-71 · 三法均值防回归。 */
class BenchmarkAverageTest {

    private static List<BigDecimal> of(double... vs) {
        return java.util.Arrays.stream(vs).mapToObj(BigDecimal::valueOf).toList();
    }

    @Test
    void geometricMeanOfConstantEqualsThatConstant() {
        // 三年都 2% → 几何均值 2%
        assertThat(BenchmarkAverage.geometricMean(of(2, 2, 2)).doubleValue()).isCloseTo(2.0, within(0.01));
    }

    @Test
    void geometricLowerThanArithmeticForVolatile() {
        // [10, -10] 算术 = 0,几何 = sqrt(1.1*0.9)-1 ≈ -0.50%
        assertThat(BenchmarkAverage.geometricMean(of(10, -10)).doubleValue()).isCloseTo(-0.50, within(0.02));
    }

    @Test
    void handlesDeflationNegativeYears() {
        // 含通缩年不报错(1+r/100 仍为正)
        BigDecimal g = BenchmarkAverage.geometricMean(of(2, -1.4, -0.8, 3));
        assertThat(g).isNotNull();
    }

    @Test
    void trimmedDropsExtremes() {
        // [-1,2,2,2,24] · trim 0.2 → drop 头尾各 1(去掉 -1 和 24)→ [2,2,2] → 2%
        assertThat(BenchmarkAverage.trimmedGeometricMean(of(24, 2, 2, 2, -1), 0.2).doubleValue())
                .isCloseTo(2.0, within(0.01));
    }

    @Test
    void trimmedFallsBackWhenTrimTooMuch() {
        // 2 项 trim 0.5 → 头尾各 drop 1 → 空 → 退回全量
        BigDecimal g = BenchmarkAverage.trimmedGeometricMean(of(10, 20), 0.5);
        assertThat(g).isEqualByComparingTo(BenchmarkAverage.geometricMean(of(10, 20)));
    }

    @Test
    void recentTakesLastN() {
        // 升序 [1,2,3,8,9] 近 2 年 = 几何(8,9)
        assertThat(BenchmarkAverage.recentAverage(of(1, 2, 3, 8, 9), 2))
                .isEqualByComparingTo(BenchmarkAverage.geometricMean(of(8, 9)));
    }

    @Test
    void emptyReturnsZero() {
        assertThat(BenchmarkAverage.geometricMean(List.of()).doubleValue()).isEqualTo(0.0);
    }
}
