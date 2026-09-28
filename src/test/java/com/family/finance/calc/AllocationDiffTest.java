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

    /**
     * v1.27 FR-873 · 「其他」类(车等)不再兜底进「投资」桶(v0.4 起的老规则把车算成投资)。
     * 不进四桶的分母,另算 otherAmount 给页面单独一行。
     */
    @Test
    void otherTypeStaysOutOfFourBuckets() {
        var entries = List.of(
            new AllocationEntry(new BigDecimal("100000"), "OTHER", null),
            new AllocationEntry(new BigDecimal("300000"), "STOCK", "SEMI_LIQUID"),
            new AllocationEntry(new BigDecimal("100000"), "CASH", "LIQUID")
        );
        var pct = AllocationDiff.computeCurrentPct(entries);
        assertThat(pct.get(Bucket.INVEST)).isEqualByComparingTo("75.00");   // 300 / 400,车不在分母里
        assertThat(pct.get(Bucket.CASH)).isEqualByComparingTo("25.00");
        assertThat(AllocationDiff.otherAmount(entries)).isEqualByComparingTo("100000");
        // 挂在别的流动性类目上也一样不进桶(先按类型短路)
        var tagged = List.of(new AllocationEntry(new BigDecimal("50000"), "OTHER", "ILLIQUID"),
                             new AllocationEntry(new BigDecimal("50000"), "CASH", "LIQUID"));
        assertThat(AllocationDiff.computeCurrentPct(tagged).get(Bucket.PROPERTY)).isEqualByComparingTo("0");
    }

    /** v1.27 FR-870 · 没有房产、没有保险:两桶不参与,标普 4321 按现金 / 投资放大 → 25 / 75 */
    @Test
    void effectiveTargetDropsAbsentBucketsAndRescales() {
        var target = Map.of(Bucket.CASH, new BigDecimal("10"), Bucket.INVEST, new BigDecimal("30"),
                Bucket.PROPERTY, new BigDecimal("40"), Bucket.INSURANCE, new BigDecimal("20"));
        var amounts = Map.of(Bucket.CASH, new BigDecimal("200"), Bucket.INVEST, new BigDecimal("800"),
                Bucket.PROPERTY, BigDecimal.ZERO, Bucket.INSURANCE, BigDecimal.ZERO);
        var eff = AllocationDiff.effectiveTarget(target, amounts);
        assertThat(eff.dropped()).containsExactly(Bucket.PROPERTY, Bucket.INSURANCE);
        assertThat(eff.rescaled()).isTrue();
        assertThat(eff.target()).containsOnlyKeys(Bucket.CASH, Bucket.INVEST);
        assertThat(eff.target().get(Bucket.CASH)).isEqualByComparingTo("25.00");
        assertThat(eff.target().get(Bucket.INVEST)).isEqualByComparingTo("75.00");
    }

    /** 只拿掉房产(有保险):现金 17 · 投资 50 · 保险 33(PRD 关键文案那一句) */
    @Test
    void effectiveTargetKeepsInsuranceWhenHeld() {
        var target = Map.of(Bucket.CASH, new BigDecimal("10"), Bucket.INVEST, new BigDecimal("30"),
                Bucket.PROPERTY, new BigDecimal("40"), Bucket.INSURANCE, new BigDecimal("20"));
        var amounts = Map.of(Bucket.CASH, BigDecimal.ONE, Bucket.INVEST, BigDecimal.ONE,
                Bucket.PROPERTY, BigDecimal.ZERO, Bucket.INSURANCE, BigDecimal.ONE);
        var eff = AllocationDiff.effectiveTarget(target, amounts);
        assertThat(eff.dropped()).containsExactly(Bucket.PROPERTY);
        assertThat(eff.target().get(Bucket.CASH)).isEqualByComparingTo("16.67");
        assertThat(eff.target().get(Bucket.INVEST)).isEqualByComparingTo("50.00");
        assertThat(eff.target().get(Bucket.INSURANCE)).isEqualByComparingTo("33.33");
    }

    /** 现金、投资桶始终参与 —— 没有投资时「投资低配」是真问题 */
    @Test
    void cashAndInvestAlwaysParticipate() {
        var target = Map.of(Bucket.CASH, new BigDecimal("10"), Bucket.INVEST, new BigDecimal("90"),
                Bucket.PROPERTY, BigDecimal.ZERO, Bucket.INSURANCE, BigDecimal.ZERO);
        var amounts = Map.of(Bucket.CASH, new BigDecimal("100"), Bucket.INVEST, BigDecimal.ZERO,
                Bucket.PROPERTY, BigDecimal.ZERO, Bucket.INSURANCE, BigDecimal.ZERO);
        var eff = AllocationDiff.effectiveTarget(target, amounts);
        assertThat(eff.target()).containsKeys(Bucket.CASH, Bucket.INVEST);
        assertThat(eff.rescaled()).isFalse();           // 拿掉的两桶原目标就是 0,不用放大
        assertThat(eff.target().get(Bucket.INVEST)).isEqualByComparingTo("90");
    }

    /** 家里有房产、有保险:四桶全参与、目标原样 —— 与 v1.26 一致 */
    @Test
    void effectiveTargetUnchangedWhenAllHeld() {
        var target = Map.of(Bucket.CASH, new BigDecimal("10"), Bucket.INVEST, new BigDecimal("30"),
                Bucket.PROPERTY, new BigDecimal("40"), Bucket.INSURANCE, new BigDecimal("20"));
        var amounts = Map.of(Bucket.CASH, BigDecimal.ONE, Bucket.INVEST, BigDecimal.ONE,
                Bucket.PROPERTY, BigDecimal.ONE, Bucket.INSURANCE, BigDecimal.ONE);
        var eff = AllocationDiff.effectiveTarget(target, amounts);
        assertThat(eff.dropped()).isEmpty();
        assertThat(eff.rescaled()).isFalse();
        assertThat(eff.target()).isEqualTo(target);
    }

    @Test
    void insuranceRoutesToInsuranceBucketDespiteSemiLiquid() {
        // v0.17 命门 · 保险产品类目 SAVINGS_INSURANCE 流动性 = SEMI_LIQUID,
        // 若按 liquidity_class 分桶会误入 INVEST;pickBucket 必须先按 type 短路到 INSURANCE 桶。
        var entries = List.of(
            new AllocationEntry(new BigDecimal("200000"), "INSURANCE", "SEMI_LIQUID"),
            new AllocationEntry(new BigDecimal("300000"), "STOCK", "SEMI_LIQUID")
        );
        var pct = AllocationDiff.computeCurrentPct(entries);
        assertThat(pct.get(Bucket.INSURANCE)).isEqualByComparingTo("40.00"); // 200/500
        assertThat(pct.get(Bucket.INVEST)).isEqualByComparingTo("60.00");    // 只有 STOCK,保险没混进来
    }

    @Test
    void insuranceRoutesToInsuranceBucketWhenLiquidityMissing() {
        // liquidity_class 缺失时,typeFallback 也要把 INSURANCE 落进保险桶
        var entries = List.of(
            new AllocationEntry(new BigDecimal("100000"), "INSURANCE", null),
            new AllocationEntry(new BigDecimal("300000"), "PROPERTY", null)
        );
        var pct = AllocationDiff.computeCurrentPct(entries);
        assertThat(pct.get(Bucket.INSURANCE)).isEqualByComparingTo("25.00");
        assertThat(pct.get(Bucket.PROPERTY)).isEqualByComparingTo("75.00");
        assertThat(pct.get(Bucket.INVEST)).isEqualByComparingTo("0.00");
    }
}
