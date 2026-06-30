package com.family.finance.calc;

import com.family.finance.calc.BenchmarkAggregator.BeatStatus;
import com.family.finance.calc.BenchmarkAggregator.BenchmarkInput;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkAggregatorTest {

    @Test
    void productCategoryBenchmarkUsedWhenPresent() {
        BigDecimal b = BenchmarkAggregator.benchmarkForAccount(
            new BigDecimal("0.128"), new BigDecimal("10.00"), "STOCK");
        assertThat(b).isEqualByComparingTo("10.00");
    }

    @Test
    void typeFallbackWhenPcMissing() {
        assertThat(BenchmarkAggregator.benchmarkForAccount(new BigDecimal("0.05"), null, "STOCK"))
            .isEqualByComparingTo("6.00");
        assertThat(BenchmarkAggregator.benchmarkForAccount(new BigDecimal("0.03"), null, "WEALTH"))
            .isEqualByComparingTo("3.00");
        assertThat(BenchmarkAggregator.benchmarkForAccount(new BigDecimal("0.004"), null, "CASH"))
            .isEqualByComparingTo("0.50");
    }

    @Test
    void diffComputedCorrectly() {
        // xirr 7.2% - benchmark 5.4% = +1.8 pp
        BigDecimal diff = BenchmarkAggregator.diffPercentPoints(new BigDecimal("0.072"), new BigDecimal("5.40"));
        assertThat(diff).isEqualByComparingTo("1.80");
    }

    @Test
    void beatStatusBoundaries() {
        assertThat(BenchmarkAggregator.beatStatus(new BigDecimal("3.0"))).isEqualTo(BeatStatus.BEAT);
        assertThat(BenchmarkAggregator.beatStatus(new BigDecimal("1.9"))).isEqualTo(BeatStatus.FLAT);
        assertThat(BenchmarkAggregator.beatStatus(new BigDecimal("-1.9"))).isEqualTo(BeatStatus.FLAT);
        assertThat(BenchmarkAggregator.beatStatus(new BigDecimal("-3.0"))).isEqualTo(BeatStatus.MISS);
        assertThat(BenchmarkAggregator.beatStatus(null)).isEqualTo(BeatStatus.NA);
    }

    @Test
    void weightedFamilyBenchmark() {
        // 300k × 8% + 100k × 3% + 200k × 0.5% = 24000 + 3000 + 1000 = 28000
        // 总 600k → 28000/600000 × 100 = 4.67%
        var entries = List.of(
            new BenchmarkInput(new BigDecimal("300000"), new BigDecimal("8.0")),
            new BenchmarkInput(new BigDecimal("100000"), new BigDecimal("3.0")),
            new BenchmarkInput(new BigDecimal("200000"), new BigDecimal("0.5"))
        );
        BigDecimal w = BenchmarkAggregator.weightedFamilyBenchmark(entries);
        assertThat(w.doubleValue()).isBetween(4.66, 4.68);
    }

    @Test
    void emptyOrZeroBalanceReturnsZero() {
        assertThat(BenchmarkAggregator.weightedFamilyBenchmark(List.of())).isEqualByComparingTo("0");
        var allZero = List.of(new BenchmarkInput(BigDecimal.ZERO, new BigDecimal("5.0")));
        assertThat(BenchmarkAggregator.weightedFamilyBenchmark(allZero)).isEqualByComparingTo("0");
    }

    // ── v0.10.5 · 同窗口口径(修「短账户累计 vs 年化预期」)──────────────────────

    @Test
    void expectedScaledToWindow() {
        // 8% 年化缩到 1 月 ≈ 0.64%;12 月 = 8.00%(年=年)
        assertThat(BenchmarkAggregator.expectedOverWindowPct(new BigDecimal("8.0"), 1).doubleValue())
            .isBetween(0.62, 0.66);
        assertThat(BenchmarkAggregator.expectedOverWindowPct(new BigDecimal("8.0"), 12))
            .isEqualByComparingTo("8.00");
        assertThat(BenchmarkAggregator.expectedOverWindowPct(null, 6)).isNull();
        assertThat(BenchmarkAggregator.expectedOverWindowPct(new BigDecimal("8.0"), 0)).isNull();
    }

    @Test
    void monthly2pct_vs_annual8pct_isBeatNotMiss() {
        // 用户的例子:某账户 1 个月累计 2%,预期年化 8% → 同窗口比应「跑赢」,绝不是「跑输 6pp」
        BigDecimal diff = BenchmarkAggregator.windowDiffPercentPoints(new BigDecimal("2.0"), new BigDecimal("8.0"), 1);
        assertThat(diff.doubleValue()).isGreaterThan(1.0);   // ≈ +1.36pp,正=跑赢
        assertThat(BenchmarkAggregator.beatStatusWindow(diff, 1)).isEqualTo(BeatStatus.BEAT);
        // 对照:旧的「年化口径」会得到 2−8 = −6pp(错判跑输)
    }

    @Test
    void windowThresholdScalesWithHorizon_12moEqualsAnnual() {
        // 满 12 期:窗口=年,阈值回到 ±2pp;实际累计 10% vs 基准 8% → +2.0pp 恰在边界 = FLAT
        BigDecimal diff = BenchmarkAggregator.windowDiffPercentPoints(new BigDecimal("10.0"), new BigDecimal("8.0"), 12);
        assertThat(diff).isEqualByComparingTo("2.00");
        assertThat(BenchmarkAggregator.beatStatusWindow(diff, 12)).isEqualTo(BeatStatus.FLAT);
        assertThat(BenchmarkAggregator.beatStatusWindow(new BigDecimal("2.5"), 12)).isEqualTo(BeatStatus.BEAT);
        assertThat(BenchmarkAggregator.beatStatusWindow(null, 6)).isEqualTo(BeatStatus.NA);
    }
}
