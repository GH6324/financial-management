package com.family.finance.web.dashboard;

import com.family.finance.factview.CashflowBreakdown;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v0.13 · 「本期怎么变的」三分:人赚 + 钱赚 + 开账基线 == ΔNW。
 * 开账基线(新纳入账户存量本金)从钱赚剔除,补录存量账户不虚高投资收益。
 */
class CashflowSplitOpeningTest {

    private static CashflowBreakdown bk(String inc, String exp) {
        BigDecimal i = new BigDecimal(inc), e = new BigDecimal(exp);
        return new CashflowBreakdown(i, e, i.subtract(e));
    }

    @Test
    void qianExcludesOpeningBaseline_andThreeAddUpToDelta() {
        // 取自 beta 真实:ΔNW +235,903 · 人赚 +61,090 · 新纳入 BTC 开账 +426,372
        var v = CashflowSplitView.of(new BigDecimal("235903"), bk("62090", "1000"),
                0, 3, new BigDecimal("426372"));
        assertThat(v.renZhuan()).isEqualByComparingTo("61090");
        assertThat(v.openingBaseline()).isEqualByComparingTo("426372");
        // 钱赚 = ΔNW − 人赚 − 开账基线 = 235903 − 61090 − 426372 = −251559(真实:其它持仓缩水)
        assertThat(v.qianZhuan()).isEqualByComparingTo("-251559");
        // 三者相加 = ΔNW(卡内自洽)
        assertThat(v.renZhuan().add(v.qianZhuan()).add(v.openingBaseline()))
                .isEqualByComparingTo("235903");
        assertThat(v.hasOpening()).isTrue();
    }

    @Test
    void noOpening_behavesLikeBefore() {
        // 无新增账户:开账基线 0,钱赚 = ΔNW − 人赚,不显第三行(与现状一致)
        var v = CashflowSplitView.of(new BigDecimal("30000"), bk("12000", "0"), 2, 3, BigDecimal.ZERO);
        assertThat(v.qianZhuan()).isEqualByComparingTo("18000");   // 30000 − 12000
        assertThat(v.hasOpening()).isFalse();
        assertThat(v.renZhuan().add(v.qianZhuan()).add(v.openingBaseline()))
                .isEqualByComparingTo("30000");
    }

    @Test
    void backwardCompatFourArg_opensZero() {
        var v = CashflowSplitView.of(new BigDecimal("30000"), bk("12000", "0"), 2, 3);
        assertThat(v.openingBaseline()).isEqualByComparingTo("0");
        assertThat(v.hasOpening()).isFalse();
        assertThat(v.qianZhuan()).isEqualByComparingTo("18000");
    }
}
