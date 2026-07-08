package com.family.finance.service.stock;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MetalUnit 单位/symbol/归一 单测 · v0.14 issue #4 回归护栏。
 */
class MetalUnitTest {

    @Test
    void tickerForMapsMetalAndSource() {
        assertThat(MetalUnit.tickerFor("AU", "sge")).isEqualTo("AU9999");
        assertThat(MetalUnit.tickerFor("AU", "intl")).isEqualTo("XAU");
        assertThat(MetalUnit.tickerFor("AG", "sge")).isEqualTo("AGTD");
        assertThat(MetalUnit.tickerFor("AG", "intl")).isEqualTo("XAG");
        assertThat(MetalUnit.tickerFor("PT", "sge")).isEqualTo("PT9995");
        assertThat(MetalUnit.tickerFor("PT", "intl")).isEqualTo("XPT");
        assertThat(MetalUnit.tickerFor("PD", "intl")).isEqualTo("XPD");
        // 钯金无上海盘 → null(UI 提示改选国际)
        assertThat(MetalUnit.tickerFor("PD", "sge")).isNull();
    }

    @Test
    void sinaSymbolMapping() {
        assertThat(MetalUnit.toSinaMetalSymbol("AU9999")).isEqualTo("gds_AU9999");
        assertThat(MetalUnit.toSinaMetalSymbol("AGTD")).isEqualTo("gds_AGTD");
        assertThat(MetalUnit.toSinaMetalSymbol("PT9995")).isEqualTo("gds_PT9995");
        assertThat(MetalUnit.toSinaMetalSymbol("XAU")).isEqualTo("hf_XAU");
        assertThat(MetalUnit.toSinaMetalSymbol("XPD")).isEqualTo("hf_XPD");
        assertThat(MetalUnit.fromSinaMetalSymbol("gds_AU9999")).isEqualTo("AU9999");
        assertThat(MetalUnit.fromSinaMetalSymbol("hf_XAU")).isEqualTo("XAU");
    }

    @Test
    void currencyAndDefaultUnitFollowSource() {
        assertThat(MetalUnit.currencyOf("AU9999")).isEqualTo("CNY");
        assertThat(MetalUnit.currencyOf("XAU")).isEqualTo("USD");
        assertThat(MetalUnit.defaultUnit("AU9999")).isEqualTo(MetalUnit.GRAM);
        assertThat(MetalUnit.defaultUnit("XAU")).isEqualTo(MetalUnit.OUNCE);
    }

    @Test
    void normalizeToPerGram_sgeGoldUnchanged() {
        assertThat(MetalUnit.normalizeToPerGram("AU9999", new BigDecimal("892.00")))
                .isEqualByComparingTo("892.00");   // SGE 金:元/克,不变
    }

    @Test
    void normalizeToPerGram_sgeSilverDividedByThousand() {
        assertThat(MetalUnit.normalizeToPerGram("AGTD", new BigDecimal("14400")))
                .isEqualByComparingTo("14.4");     // SGE 银:元/千克 → 元/克
    }

    @Test
    void normalizeToPerGram_internationalDividedByTroyOunce() {
        // 每盎司 31.1035 → 每克 = 1
        assertThat(MetalUnit.normalizeToPerGram("XAU", new BigDecimal("31.1035")))
                .isEqualByComparingTo("1");
    }

    @Test
    void perHoldingUnit_ounceScalesByTroyOunce_gramUnchanged() {
        assertThat(MetalUnit.perHoldingUnit(MetalUnit.OUNCE, new BigDecimal("1")))
                .isEqualByComparingTo("31.1035");
        assertThat(MetalUnit.perHoldingUnit(MetalUnit.GRAM, new BigDecimal("892")))
                .isEqualByComparingTo("892");
        // null 单位按克
        assertThat(MetalUnit.perHoldingUnit(null, new BigDecimal("892")))
                .isEqualByComparingTo("892");
    }

    @Test
    void roundTrip_internationalOunce() {
        // 伦敦金 4070.94 USD/oz → 每克归一 → 再按 OUNCE 还原 ≈ 4070.94(8dp 内)
        BigDecimal perGram = MetalUnit.normalizeToPerGram("XAU", new BigDecimal("4070.94"));
        BigDecimal backToOunce = MetalUnit.perHoldingUnit(MetalUnit.OUNCE, perGram);
        assertThat(backToOunce.subtract(new BigDecimal("4070.94")).abs())
                .isLessThan(new BigDecimal("0.001"));
    }
}
