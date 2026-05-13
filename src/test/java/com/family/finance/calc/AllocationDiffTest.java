package com.family.finance.calc;

import com.family.finance.calc.AllocationDiff.AllocationEntry;
import com.family.finance.calc.AllocationDiff.Bucket;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AllocationDiffTest {

    @Test
    void emptyInputsReturnZero() {
        var pct = AllocationDiff.computeCurrentPct(List.of());
        assertThat(pct.get(Bucket.CASH)).isEqualByComparingTo("0");
        assertThat(pct.get(Bucket.INVEST)).isEqualByComparingTo("0");
        assertThat(pct.get(Bucket.PROPERTY)).isEqualByComparingTo("0");
    }

    @Test
    void liquidityClassDrivesBucket() {
        // 余额宝 100k LIQUID → CASH;A 股 200k SEMI_LIQUID → INVEST;住宅 700k ILLIQUID → PROPERTY
        var entries = List.of(
            new AllocationEntry(new BigDecimal("100000"), "WEALTH", "LIQUID"),
            new AllocationEntry(new BigDecimal("200000"), "STOCK", "SEMI_LIQUID"),
            new AllocationEntry(new BigDecimal("700000"), "PROPERTY", "ILLIQUID")
        );
        var pct = AllocationDiff.computeCurrentPct(entries);
        assertThat(pct.get(Bucket.CASH)).isEqualByComparingTo("10.00");
        assertThat(pct.get(Bucket.INVEST)).isEqualByComparingTo("20.00");
        assertThat(pct.get(Bucket.PROPERTY)).isEqualByComparingTo("70.00");
    }

    @Test
    void accountTypeFallbackWhenLiquidityMissing() {
        var entries = List.of(
            new AllocationEntry(new BigDecimal("100000"), "CASH", null),
            new AllocationEntry(new BigDecimal("200000"), "STOCK", null),
            new AllocationEntry(new BigDecimal("100000"), "WEALTH", null),
            new AllocationEntry(new BigDecimal("600000"), "PROPERTY", null)
        );
        var pct = AllocationDiff.computeCurrentPct(entries);
        assertThat(pct.get(Bucket.CASH)).isEqualByComparingTo("10.00");
        assertThat(pct.get(Bucket.INVEST)).isEqualByComparingTo("30.00"); // STOCK + WEALTH
        assertThat(pct.get(Bucket.PROPERTY)).isEqualByComparingTo("60.00");
    }

    @Test
    void loanIsExcludedFromAssetDenominator() {
        // LOAN 不计入资产分母 · 100k 现金 + 200k 投资 + 500k 房产 = 800k 总,LOAN 负 200k 被跳过
        var entries = List.of(
            new AllocationEntry(new BigDecimal("100000"), "CASH", "LIQUID"),
            new AllocationEntry(new BigDecimal("200000"), "STOCK", "SEMI_LIQUID"),
            new AllocationEntry(new BigDecimal("500000"), "PROPERTY", "ILLIQUID"),
            new AllocationEntry(new BigDecimal("200000"), "LOAN", null)
        );
        var pct = AllocationDiff.computeCurrentPct(entries);
        // 总资产 = 800k(LOAN 跳过)· 100/800 = 12.50%
        assertThat(pct.get(Bucket.CASH)).isEqualByComparingTo("12.50");
        assertThat(pct.get(Bucket.INVEST)).isEqualByComparingTo("25.00");
        assertThat(pct.get(Bucket.PROPERTY)).isEqualByComparingTo("62.50");
    }

    @Test
    void diffPositiveMeansOver() {
        var current = Map.of(
            Bucket.CASH, new BigDecimal("3.40"),
            Bucket.INVEST, new BigDecimal("36.80"),
            Bucket.PROPERTY, new BigDecimal("59.80"),
            Bucket.INSURANCE, BigDecimal.ZERO
        );
        var target = Map.of(
            Bucket.CASH, new BigDecimal("10"),
            Bucket.INVEST, new BigDecimal("30"),
            Bucket.PROPERTY, new BigDecimal("40"),
            Bucket.INSURANCE, new BigDecimal("20")
        );
        var diff = AllocationDiff.diff(current, target);
        assertThat(diff.get(Bucket.CASH).doubleValue()).isCloseTo(-6.60, org.assertj.core.data.Offset.offset(0.01));
        assertThat(diff.get(Bucket.INVEST).doubleValue()).isCloseTo(6.80, org.assertj.core.data.Offset.offset(0.01));
        assertThat(diff.get(Bucket.PROPERTY).doubleValue()).isCloseTo(19.80, org.assertj.core.data.Offset.offset(0.01));
        assertThat(diff.get(Bucket.INSURANCE).doubleValue()).isCloseTo(-20.00, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void otherTypeGoesToInvest() {
        var entries = List.of(
            new AllocationEntry(new BigDecimal("100000"), "OTHER", null)
        );
        var pct = AllocationDiff.computeCurrentPct(entries);
        assertThat(pct.get(Bucket.INVEST)).isEqualByComparingTo("100.00");
    }
}
