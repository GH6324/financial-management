package com.family.finance.calc;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v0.6 · AI 资产洞察 4 个纯函数守护(集中度/资产负债表/再平衡偏离/行为启发式)。
 */
class AssetInsightCalcTest {

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    // ---------------- 集中度 ----------------
    @Test
    void concentration_pct_and_line() {
        assertThat(ConcentrationCalculator.pct(bd("328"), bd("420"))).isEqualByComparingTo("78.1");
        assertThat(ConcentrationCalculator.pct(bd("10"), BigDecimal.ZERO)).isNull();           // whole 0 → 降级
        assertThat(ConcentrationCalculator.topPct(List.of(bd("36"), bd("12"), bd("7")), bd("55"))).isEqualByComparingTo("65.5");
        var over = ConcentrationCalculator.line(bd("328"), bd("420"), bd("70"));
        assertThat(over.overLine()).isTrue();
        var under = ConcentrationCalculator.line(bd("12"), bd("420"), bd("70"));
        assertThat(under.overLine()).isFalse();
    }

    // ---------------- 资产负债表 ----------------
    @Test
    void balance_sheet_bands_and_prepay() {
        var r = BalanceSheetHealth.evaluate(bd("92"), bd("328"), bd("90"), bd("420"),
                bd("4.1"), bd("1.8"));   // 房贷 4.1% > 真实收益 1.8%
        assertThat(r.financialPct()).isEqualByComparingTo("21.9");
        assertThat(r.propertyPct()).isEqualByComparingTo("78.1");
        assertThat(r.debtRatioPct()).isEqualByComparingTo("21.4");
        assertThat(r.debtBand()).isEqualTo("HEALTHY");
        assertThat(r.prepaySignal()).isTrue();
    }

    @Test
    void balance_sheet_band_thresholds_and_missing_rate() {
        assertThat(BalanceSheetHealth.evaluate(bd("50"), bd("50"), bd("40"), bd("100"), null, null).debtBand()).isEqualTo("ELEVATED");
        assertThat(BalanceSheetHealth.evaluate(bd("40"), bd("60"), bd("60"), bd("100"), null, null).debtBand()).isEqualTo("ALERT");
        // 缺利率/收益 → prepaySignal null(降级)
        assertThat(BalanceSheetHealth.evaluate(bd("50"), bd("50"), bd("20"), bd("100"), null, bd("1.8")).prepaySignal()).isNull();
    }

    // ---------------- 再平衡偏离 ----------------
    @Test
    void rebalance_drift_flags_over_under() {
        var drifts = RebalanceDrift.evaluate(
                Map.of("invest", bd("40"), "cash", bd("30"), "property", bd("30")),
                Map.of("invest", bd("60"), "cash", bd("25"), "property", bd("15")),
                bd("10"));
        var invest = drifts.stream().filter(d -> d.bucket().equals("invest")).findFirst().orElseThrow();
        assertThat(invest.overThreshold()).isTrue();
        assertThat(invest.direction()).isEqualTo("OVER");    // 超配 +20pp
        var cash = drifts.stream().filter(d -> d.bucket().equals("cash")).findFirst().orElseThrow();
        assertThat(cash.overThreshold()).isFalse();          // −5pp 未超阈值
        var prop = drifts.stream().filter(d -> d.bucket().equals("property")).findFirst().orElseThrow();
        assertThat(prop.direction()).isEqualTo("UNDER");     // 低配 −15pp
    }

    // ---------------- 行为启发式 ----------------
    @Test
    void behavior_detects_pro_cyclical_and_rising_concentration() {
        // 净流入集中在净资产高位期 → 追涨;集中度逐期单调升 +12pp → 从不止盈
        List<BehaviorHeuristics.Point> series = List.of(
                new BehaviorHeuristics.Point(bd("100"), bd("0")),
                new BehaviorHeuristics.Point(bd("105"), bd("0")),
                new BehaviorHeuristics.Point(bd("110"), bd("0")),
                new BehaviorHeuristics.Point(bd("130"), bd("5")),
                new BehaviorHeuristics.Point(bd("140"), bd("6")),
                new BehaviorHeuristics.Point(bd("150"), bd("7")));
        List<BigDecimal> conc = List.of(bd("48"), bd("50"), bd("53"), bd("56"), bd("58"), bd("60"));
        var signals = BehaviorHeuristics.detect(series, conc, 6);
        assertThat(signals).extracting(BehaviorHeuristics.Signal::code)
                .contains("PRO_CYCLICAL", "CONCENTRATION_RISING");
    }

    @Test
    void behavior_silent_when_history_too_short() {
        List<BehaviorHeuristics.Point> series = List.of(
                new BehaviorHeuristics.Point(bd("100"), bd("5")),
                new BehaviorHeuristics.Point(bd("150"), bd("9")));
        assertThat(BehaviorHeuristics.detect(series, null, 6)).isEmpty();
    }
}
