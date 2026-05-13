package com.family.finance.calc;

import com.family.finance.calc.RefinanceNpvCalculator.Input;
import com.family.finance.calc.RefinanceNpvCalculator.Recommendation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefinanceNpvCalculatorTest {

    @Test
    void rateLoanHigherThanInvestForcesMustPrepay() {
        // 老贷款 5.8% > 投资预期 5.2% → 必还
        var in = new Input(
            new BigDecimal("100000"),
            new BigDecimal("0.058"),
            new BigDecimal("0.052"),
            18,
            new BigDecimal("6"));
        var r = RefinanceNpvCalculator.compute(in);
        assertThat(r.recommendation()).isEqualTo(Recommendation.MUST_PREPAY);
    }

    @Test
    void investWhenRatesFavorIt() {
        // 房贷 4.5% · 投资 7.2% · 18 年 · 应急 6 月
        var in = new Input(
            new BigDecimal("100000"),
            new BigDecimal("0.045"),
            new BigDecimal("0.072"),
            18,
            new BigDecimal("6"));
        var r = RefinanceNpvCalculator.compute(in);
        assertThat(r.recommendation()).isEqualTo(Recommendation.INVEST);
        assertThat(r.diffNpv().signum()).isPositive();
    }

    @Test
    void lowEmergencyRefuses() {
        var in = new Input(
            new BigDecimal("100000"),
            new BigDecimal("0.045"),
            new BigDecimal("0.072"),
            18,
            new BigDecimal("2"));
        var r = RefinanceNpvCalculator.compute(in);
        assertThat(r.recommendation()).isEqualTo(Recommendation.REFUSE_LOW_EMERGENCY);
    }

    @Test
    void exactlyThreeMonthsEmergencyAllowed() {
        var in = new Input(
            new BigDecimal("100000"),
            new BigDecimal("0.045"),
            new BigDecimal("0.072"),
            18,
            new BigDecimal("3"));
        var r = RefinanceNpvCalculator.compute(in);
        assertThat(r.recommendation()).isNotEqualTo(Recommendation.REFUSE_LOW_EMERGENCY);
    }

    @Test
    void emergencyNullSkipsCheck() {
        var in = new Input(
            new BigDecimal("100000"),
            new BigDecimal("0.045"),
            new BigDecimal("0.072"),
            18,
            null);
        var r = RefinanceNpvCalculator.compute(in);
        assertThat(r.recommendation()).isEqualTo(Recommendation.INVEST);
    }

    @Test
    void zeroOrNegativeAmountThrows() {
        var in = new Input(BigDecimal.ZERO, new BigDecimal("0.045"), new BigDecimal("0.072"), 18, null);
        assertThatThrownBy(() -> RefinanceNpvCalculator.compute(in))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void outOfRangeRateThrows() {
        var bad = new Input(new BigDecimal("100000"), new BigDecimal("0.6"), new BigDecimal("0.072"), 18, null);
        assertThatThrownBy(() -> RefinanceNpvCalculator.compute(bad))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resultIncludesAllFields() {
        var in = new Input(
            new BigDecimal("100000"),
            new BigDecimal("0.045"),
            new BigDecimal("0.072"),
            18,
            new BigDecimal("6"));
        var r = RefinanceNpvCalculator.compute(in);
        assertThat(r.prepaySaved()).isNotNull();
        assertThat(r.investGain()).isNotNull();
        assertThat(r.diffNpv()).isNotNull();
        assertThat(r.reason()).isNotBlank();
    }
}
