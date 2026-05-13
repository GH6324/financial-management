package com.family.finance.calc;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LiquiditySurplusTest {

    @Test
    void noExpenseReturnsNoNotify() {
        var r = LiquiditySurplus.evaluate(new BigDecimal("500000"), BigDecimal.ZERO, 6);
        assertThat(r.shouldNotify()).isFalse();
    }

    @Test
    void underNeededDoesNotNotify() {
        // 月支出 18k · 6 月需求 = 108k · 阈值 1.5x = 162k · LIQUID 90k < 需求,不通知
        var r = LiquiditySurplus.evaluate(new BigDecimal("90000"), new BigDecimal("18000"), 6);
        assertThat(r.shouldNotify()).isFalse();
        assertThat(r.surplus()).isEqualByComparingTo("0");
    }

    @Test
    void atOneXNotYetNotify() {
        // 刚好等于需求,不通知(还没到 1.5x)
        var r = LiquiditySurplus.evaluate(new BigDecimal("108000"), new BigDecimal("18000"), 6);
        assertThat(r.shouldNotify()).isFalse();
    }

    @Test
    void over15XTriggersNotify() {
        // 18k × 6 × 1.5 = 162k · LIQUID 185k > 阈值,通知
        var r = LiquiditySurplus.evaluate(new BigDecimal("185000"), new BigDecimal("18000"), 6);
        assertThat(r.shouldNotify()).isTrue();
        // surplus = 185k - 108k = 77k
        assertThat(r.surplus()).isEqualByComparingTo("77000.00");
    }

    @Test
    void customEmergencyMonthsRespected() {
        // 12 月应急 · 18k × 12 = 216k 需求 · 1.5x = 324k
        var r = LiquiditySurplus.evaluate(new BigDecimal("250000"), new BigDecimal("18000"), 12);
        assertThat(r.shouldNotify()).isFalse(); // 250k < 324k
        assertThat(r.emergencyNeeded()).isEqualByComparingTo("216000.00");
    }

    @Test
    void nullMonthsUsesDefault6() {
        var r = LiquiditySurplus.evaluate(new BigDecimal("100000"), new BigDecimal("10000"), null);
        assertThat(r.emergencyMonths()).isEqualTo(6);
    }
}
