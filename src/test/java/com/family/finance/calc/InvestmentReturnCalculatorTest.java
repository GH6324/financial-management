package com.family.finance.calc;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InvestmentReturnCalculatorTest {

    @Test
    void monthlyComputesPnlAndPct() {
        // 期初 100k · 期末 105k · 本月净流入 2k(工资 2k)→ PnL = 105 - 2 - 100 = 3k · 比率 = 3k/100k = 3%
        var r = InvestmentReturnCalculator.monthly(
            new BigDecimal("100000"),
            new BigDecimal("105000"),
            new BigDecimal("2000"));
        assertThat(r.pnlAmount()).isEqualByComparingTo("3000.00");
        assertThat(r.pnlPct().doubleValue()).isCloseTo(0.03, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(r.isPositive()).isTrue();
    }

    @Test
    void monthlyHandlesNegativePnl() {
        // 资产跌了 · 期初 200k · 期末 198k · 净流入 3k(工资 - 消费)→ PnL = 198 - 3 - 200 = -5k
        var r = InvestmentReturnCalculator.monthly(
            new BigDecimal("200000"),
            new BigDecimal("198000"),
            new BigDecimal("3000"));
        assertThat(r.pnlAmount()).isEqualByComparingTo("-5000.00");
        assertThat(r.isNegative()).isTrue();
    }

    @Test
    void monthlyHandlesZeroStartValue() {
        var r = InvestmentReturnCalculator.monthly(
            BigDecimal.ZERO, new BigDecimal("100"), new BigDecimal("100"));
        assertThat(r.pnlAmount()).isNull();
        assertThat(r.pnlPct()).isNull();
    }

    @Test
    void monthlyHandlesNull() {
        var r = InvestmentReturnCalculator.monthly(null, new BigDecimal("100"), BigDecimal.ZERO);
        assertThat(r.pnlAmount()).isNull();
    }

    @Test
    void annualizedRolling12FromSeriesGeometric() {
        // 12 个月各 +1% · 几何累计 = 1.01^12 - 1 ≈ 12.68%
        List<TwrCalculator.TwrPoint> series = java.util.stream.IntStream.range(0, 12)
            .mapToObj(i -> new TwrCalculator.TwrPoint(
                new BigDecimal("100"), new BigDecimal("101"), BigDecimal.ZERO))
            .toList();
        BigDecimal r = InvestmentReturnCalculator.annualizedRolling12(series);
        // annualizedOrCumulative · 12 月会做年化 · 12/12 = 1 次方根 ≈ 12.68%
        assertThat(r.doubleValue()).isCloseTo(0.1268, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void annualizedRolling12With6MonthsReturnsCumulative() {
        // 6 月各 +2% · 累计 = 1.02^6 - 1 ≈ 12.62% · 不足 12 月不年化
        List<TwrCalculator.TwrPoint> series = java.util.stream.IntStream.range(0, 6)
            .mapToObj(i -> new TwrCalculator.TwrPoint(
                new BigDecimal("100"), new BigDecimal("102"), BigDecimal.ZERO))
            .toList();
        BigDecimal r = InvestmentReturnCalculator.annualizedRolling12(series);
        assertThat(r.doubleValue()).isCloseTo(0.1262, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void annualizedRolling12HandlesEmpty() {
        assertThat(InvestmentReturnCalculator.annualizedRolling12(null)).isNull();
        assertThat(InvestmentReturnCalculator.annualizedRolling12(List.of())).isNull();
    }

    @Test
    void ytdPnlAmount() {
        // 3 个月 PnL · 各 +3k -1k +2k = 4k 累计
        List<TwrCalculator.TwrPoint> series = List.of(
            new TwrCalculator.TwrPoint(new BigDecimal("100000"), new BigDecimal("103000"), BigDecimal.ZERO),
            new TwrCalculator.TwrPoint(new BigDecimal("103000"), new BigDecimal("102000"), BigDecimal.ZERO),
            new TwrCalculator.TwrPoint(new BigDecimal("102000"), new BigDecimal("104000"), BigDecimal.ZERO)
        );
        BigDecimal ytd = InvestmentReturnCalculator.ytdPnlAmount(series);
        assertThat(ytd).isEqualByComparingTo("4000.00");
    }

    @Test
    void ytdPnlAmountWithNetInflow() {
        // 月份 PnL = end - inflow - start
        // 100k → 110k · 本月净流入 8k → PnL = 110-8-100 = 2k
        // 110k → 115k · 净流入 -3k(消费多)→ PnL = 115-(-3)-110 = 8k
        List<TwrCalculator.TwrPoint> series = List.of(
            new TwrCalculator.TwrPoint(new BigDecimal("100000"), new BigDecimal("110000"), new BigDecimal("8000")),
            new TwrCalculator.TwrPoint(new BigDecimal("110000"), new BigDecimal("115000"), new BigDecimal("-3000"))
        );
        BigDecimal ytd = InvestmentReturnCalculator.ytdPnlAmount(series);
        assertThat(ytd).isEqualByComparingTo("10000.00");
    }
}
