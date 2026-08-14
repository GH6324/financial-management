package com.family.finance.common;

import com.family.finance.service.report.SealedSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.12 FR-353 护栏 {@code v112-RATIO-INSUFFICIENT} 的属性侧。
 *
 * <p>守三条:① 正常范围的比率<b>不许</b>被降级(这条是验收标准原文:「正常范围的值显示不受影响」)
 * ② prod 见过的 −2383% 必须被降级 ③ <b>金额类</b>不许被这条规则误伤 ——
 * 净资产 100 万的数值远大于阈值 5,如果判断里漏了 {@code ratio} 这个前提,
 * 报表页三列对照的净资产那一行会整行变成「收支不足」,比原来的问题严重得多。</p>
 */
class MetricDisplayTest {

    private static SealedSnapshot.ComparisonRow ratioRow(String v) {
        return new SealedSnapshot.ComparisonRow("储蓄率", new BigDecimal(v), null, null, true, false);
    }

    private static SealedSnapshot.ComparisonRow moneyRow(String v) {
        return new SealedSnapshot.ComparisonRow("净资产", new BigDecimal(v), null, null, false, false);
    }

    @Test
    void normalRatiosAreNeverDegraded() {
        // 家庭真实可能出现的区间:攒下九成 / 收支平衡 / 支出是收入两倍 / 支出是收入六倍
        for (String v : new String[]{"0.9", "0.48", "0", "-1", "-5", "5"}) {
            assertThat(MetricDisplay.ratioAbsurd(new BigDecimal(v)))
                    .as("正常范围 %s 不该被降级", v).isFalse();
        }
    }

    @Test
    void absurdRatiosAreDegraded() {
        // prod 实际值:某期收入 300、支出 7450 → (300−7450)/300 = −23.8333…
        assertThat(MetricDisplay.ratioAbsurd(new BigDecimal("-23.8333"))).isTrue();
        assertThat(MetricDisplay.ratioAbsurd(new BigDecimal("5.01"))).isTrue();
        assertThat(MetricDisplay.ratioAbsurd(new BigDecimal("-5.01"))).isTrue();
    }

    /** 阈值是「没有数据」之外的另一件事:null 走 `—`,不走降级文案。 */
    @Test
    void nullIsNotAbsurd() {
        assertThat(MetricDisplay.ratioAbsurd(null)).isFalse();
    }

    @Test
    void thresholdIs500Percent() {
        assertThat(MetricDisplay.RATIO_ABSURD_ABS).isEqualByComparingTo("5");
        assertThat(MetricDisplay.NOTE.insufficient()).isEqualTo("收支数据不足");
        assertThat(MetricDisplay.NOTE.backfillHref()).isEqualTo("/entry");
    }

    @Test
    void moneyRowsAreNotDegradedByRatioRule() {
        assertThat(moneyRow("1000000").absurd(new BigDecimal("1000000"))).isFalse();
        assertThat(ratioRow("-23.8333").absurd(new BigDecimal("-23.8333"))).isTrue();
        assertThat(ratioRow("0.48").absurd(new BigDecimal("0.48"))).isFalse();
    }

    /**
     * 值列降级了,由它派生的 Δ 列不能还在。
     *
     * <p>2026-08-14 beta 双端复验实拍到的:本期 = 「收支不足」,同比 = 「−2468.2 pp」 ——
     * 那个 pp 就是拿藏起来的 −2383.3% 减 84.86% 得的,比直接摆原值更坏(看不出一端是垃圾值)。
     * 所以差额在任一端点失真时返回 null,页面走既有的 `—` 分支。</p>
     */
    @Test
    void deltasAreDroppedWhenEitherEndpointIsAbsurd() {
        var absurdVsNormal = new SealedSnapshot.ComparisonRow(
                "储蓄率", new BigDecimal("-23.8333"), new BigDecimal("0.48"), new BigDecimal("0.8486"), true, false);
        assertThat(absurdVsNormal.momDelta()).as("本期失真 → 环比不给 pp").isNull();
        assertThat(absurdVsNormal.yoyDelta()).as("本期失真 → 同比不给 pp").isNull();

        var normalVsAbsurd = new SealedSnapshot.ComparisonRow(
                "储蓄率", new BigDecimal("0.48"), new BigDecimal("-23.8333"), new BigDecimal("0.8486"), true, false);
        assertThat(normalVsAbsurd.momDelta()).as("上期失真 → 环比同样不给").isNull();
        assertThat(normalVsAbsurd.yoyDelta()).as("两端都正常 → 照给")
                .isEqualByComparingTo("-0.3686");

        // 金额类不受影响:净资产 100 万远大于阈值 5,但它不是比率
        var money = new SealedSnapshot.ComparisonRow(
                "净资产", new BigDecimal("4208403"), new BigDecimal("2757855"), null, false, false);
        assertThat(money.momDelta()).isEqualByComparingTo("1450548");
    }
}
