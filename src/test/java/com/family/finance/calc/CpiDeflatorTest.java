package com.family.finance.calc;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CpiDeflatorTest {

    @Test
    void zeroOrNegativeCpiReturnsNominal() {
        var nominal = List.of(new BigDecimal("100"), new BigDecimal("200"));
        assertThat(CpiDeflator.deflateMonthly(nominal, BigDecimal.ZERO))
            .containsExactly(new BigDecimal("100.00"), new BigDecimal("200.00"));
        assertThat(CpiDeflator.deflateMonthly(nominal, new BigDecimal("-1")))
            .containsExactly(new BigDecimal("100.00"), new BigDecimal("200.00"));
    }

    @Test
    void basePeriodEqualsNominal() {
        var nominal = List.of(new BigDecimal("1000000"));
        var result = CpiDeflator.deflateMonthly(nominal, new BigDecimal("2.0"));
        // i=0 → divisor=1 → 实际 = 名义
        assertThat(result.get(0)).isEqualByComparingTo("1000000.00");
    }

    @Test
    void twoPctMonthlyDeflationApproximatesAnnualDrop() {
        // 12 个月后,2% 年通胀 → 实际购买力约 100/1.02 ≈ 98.04
        var nominal = java.util.stream.IntStream.range(0, 13)
            .mapToObj(i -> new BigDecimal("100"))
            .toList();
        var result = CpiDeflator.deflateMonthly(nominal, new BigDecimal("2.0"));
        // 末点 (i=12): 100 / (1 + 0.02/12)^12 ≈ 100 / 1.02018 ≈ 98.02
        assertThat(result.get(12).doubleValue()).isBetween(97.95, 98.10);
    }

    @Test
    void higherCpiCausesLargerDeflation() {
        var nominal = List.of(new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"));
        var atTwoPct = CpiDeflator.deflateMonthly(nominal, new BigDecimal("2.0"));
        var atFivePct = CpiDeflator.deflateMonthly(nominal, new BigDecimal("5.0"));
        assertThat(atFivePct.get(2).doubleValue()).isLessThan(atTwoPct.get(2).doubleValue());
    }

    @Test
    void nullInputThrows() {
        assertThatThrownBy(() -> CpiDeflator.deflateMonthly(null, BigDecimal.ONE))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullElementsArePreserved() {
        var nominal = java.util.Arrays.asList(new BigDecimal("100"), null, new BigDecimal("200"));
        var result = CpiDeflator.deflateMonthly(nominal, new BigDecimal("2.0"));
        assertThat(result.get(0)).isEqualByComparingTo("100.00");
        assertThat(result.get(1)).isNull();
        assertThat(result.get(2)).isNotNull();
    }

    @Test
    void singlePointDeflation() {
        // 24 个月后 3% 年通胀 → 100 / (1.0025)^24 ≈ 94.20
        var result = CpiDeflator.deflateSinglePoint(new BigDecimal("100"), 24, new BigDecimal("3.0"));
        assertThat(result.doubleValue()).isBetween(94.10, 94.30);
    }
}
