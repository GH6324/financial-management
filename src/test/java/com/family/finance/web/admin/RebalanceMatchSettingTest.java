package com.family.finance.web.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.26 · 再平衡「算执行了」的比例:页面填百分比,库里存小数。
 *
 * <p>守存储格式 —— {@code RebalancePlanService.onTransfer} 从 v1.2 起一直按小数读(默认 0.8)。
 * 页面若把「80」原样存进去,核销门槛就成了计划金额的 80 倍,再也不会有条目被标成已执行,而且不报错。</p>
 */
class RebalanceMatchSettingTest {

    @Test
    void 百分比存成小数_和读的一边同一种格式() {
        assertThat(AdminController.rebalanceMatchFraction(80)).isEqualTo("0.8");
        assertThat(AdminController.rebalanceMatchFraction(95)).isEqualTo("0.95");
        assertThat(Double.parseDouble(AdminController.rebalanceMatchFraction(100))).isEqualTo(1.0);
    }

    @Test
    void 越界夹回_50到100() {
        assertThat(AdminController.rebalanceMatchFraction(30)).isEqualTo("0.5");
        assertThat(Double.parseDouble(AdminController.rebalanceMatchFraction(150))).isEqualTo(1.0);
    }

    @Test
    void 读出来显示成百分比() {
        assertThat(AdminController.rebalanceMatchPercent(0.8)).isEqualTo(80);
        assertThat(AdminController.rebalanceMatchPercent(0.95)).isEqualTo(95);
    }
}
