package com.family.finance.web.dashboard;

import com.family.finance.factview.CashflowBreakdown;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v0.10 · 「人赚 vs 钱赚」视图模型纯逻辑护栏。
 *
 * <p>钉死评审暴露的四象限符号问题 + 首期/空/半填三态 + 双向条宽度(比例)。纯函数,无 Spring/mock。</p>
 */
class CashflowSplitViewTest {

    private static BigDecimal bd(String s) { return new BigDecimal(s); }
    private static CashflowBreakdown brk(String inc, String exp) {
        BigDecimal i = bd(inc), e = bd(exp);
        return new CashflowBreakdown(i, e, i.subtract(e));
    }

    @Test
    void firstPeriod_whenDeltaNull_onlyRenZhuan_noQian() {
        CashflowSplitView v = CashflowSplitView.of(null, brk("1000", "400"), 2, 2);
        assertThat(v.firstPeriod()).isTrue();
        assertThat(v.qianZhuan()).isNull();
        assertThat(v.renZhuan()).isEqualByComparingTo(bd("600"));
        assertThat(v.narrative()).contains("首期");
    }

    @Test
    void quadrant_bothPositive() {
        CashflowSplitView v = CashflowSplitView.of(bd("28200"), brk("38000", "26000"), 3, 3);
        assertThat(v.renZhuan()).isEqualByComparingTo(bd("12000"));
        assertThat(v.qianZhuan()).isEqualByComparingTo(bd("16200"));   // 28200 − 12000
        assertThat(v.renPos()).isTrue();
        assertThat(v.qianPos()).isTrue();
        assertThat(v.deltaPos()).isTrue();
        assertThat(v.narrative()).contains("一起把净资产推高");
    }

    @Test
    void quadrant_savedButInvestmentDraggedDown_deltaNegative() {
        // 人赚 +12000,钱赚 −20000 → ΔNW −8000
        CashflowSplitView v = CashflowSplitView.of(bd("-8000"), brk("38000", "26000"), 2, 2);
        assertThat(v.renPos()).isTrue();
        assertThat(v.qianPos()).isFalse();
        assertThat(v.qianZhuan()).isEqualByComparingTo(bd("-20000"));
        assertThat(v.deltaPos()).isFalse();
        assertThat(v.narrative()).contains("回撤");
    }

    @Test
    void quadrant_overspentButInvestmentCovered_deltaPositive() {
        // 人赚 −3000(超支),钱赚 +10000 → ΔNW +7000
        CashflowSplitView v = CashflowSplitView.of(bd("7000"), brk("10000", "13000"), 2, 2);
        assertThat(v.renPos()).isFalse();
        assertThat(v.qianPos()).isTrue();
        assertThat(v.qianZhuan()).isEqualByComparingTo(bd("10000"));
        assertThat(v.narrative()).contains("超支");
        assertThat(v.narrative()).contains("结余");
    }

    @Test
    void quadrant_bothNegative() {
        CashflowSplitView v = CashflowSplitView.of(bd("-13000"), brk("10000", "13000"), 2, 2);
        assertThat(v.renPos()).isFalse();
        assertThat(v.qianPos()).isFalse();
        assertThat(v.qianZhuan()).isEqualByComparingTo(bd("-10000"));
        assertThat(v.narrative()).contains("双双下行");
    }

    @Test
    void completeness_emptyAndPartialStates() {
        assertThat(CashflowSplitView.of(bd("5000"), brk("0", "0"), 0, 3).empty()).isTrue();
        CashflowSplitView partial = CashflowSplitView.of(bd("5000"), brk("4200", "0"), 1, 3);
        assertThat(partial.empty()).isFalse();
        assertThat(partial.partial()).isTrue();
        CashflowSplitView full = CashflowSplitView.of(bd("5000"), brk("4200", "0"), 3, 3);
        assertThat(full.partial()).isFalse();
        assertThat(full.empty()).isFalse();
    }

    @Test
    void dualBarWidths_areProportionalToMaxAbs() {
        // 人赚 5000 / 钱赚 7000 / ΔNW 12000 · max=12000 · 半宽 = |v|/max×50
        CashflowSplitView v = CashflowSplitView.of(bd("12000"), brk("5000", "0"), 2, 2);
        assertThat(v.renWidth()).isEqualTo(21);    // 5000/12000×50 = 20.83 → 21
        assertThat(v.qianWidth()).isEqualTo(29);   // 7000/12000×50 = 29.17 → 29
        assertThat(v.deltaWidth()).isEqualTo(50);  // 最大绝对值占满半宽
    }
}
