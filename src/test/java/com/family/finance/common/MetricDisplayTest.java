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
}
