package com.family.finance.service.ask;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图表标记的括号容错,穷举。
 *
 * <h3>为什么必须是单测</h3>
 *
 * <p>这个错要靠模型<b>恰好少写一个右花括号</b>才复现 —— 在浏览器里造不出来,
 * 只能守株待兔。2026-09-22 在 beta 上真撞到一次:一张六项的资产类型饼图就这么丢了,
 * 而正文里「钱主要分在六类里……<b>:</b>」那个冒号还留在页面上,后面空空如也。</p>
 *
 * <p>根因是<b>分隔符撞车</b>:标记以 {@code }}} 结束,JSON 对象也以 {@code }} 结束,
 * 模型写 {@code ...]}}}} 时很容易少打一个。这不是模型「犯错」,是我们选的分隔符
 * 天然歧义 —— 所以容错应该在我们这边。</p>
 */
class AskChartMarkerTest {

    @Test
    @DisplayName("正常的 JSON 直接解析")
    void wellFormed() {
        assertThat(AskCitationRenderer.parseChartJson(
                "{\"type\":\"pie\",\"items\":[{\"label\":\"a\",\"cite\":\"t1_r0_0\"}]}"))
                .isNotNull();
    }

    @Test
    @DisplayName("少一个右花括号 → 补回来(beta 实测的那一张就是这样)")
    void missingOneBrace_isRepaired() {
        var n = AskCitationRenderer.parseChartJson(
                "{\"type\":\"pie\",\"title\":\"按资产类型分布\",\"items\":["
                + "{\"label\":\"债券理财\",\"cite\":\"t2_r0_0\"},"
                + "{\"label\":\"房产\",\"cite\":\"t2_r1_0\"}]");
        assertThat(n).isNotNull();
        assertThat(n.path("items")).hasSize(2);
        assertThat(n.path("items").get(0).path("cite").asText()).isEqualTo("t2_r0_0");
    }

    @Test
    @DisplayName("少两个也补 —— 嵌套一层时会少两个")
    void missingTwoBraces_isRepaired() {
        assertThat(AskCitationRenderer.parseChartJson(
                "{\"type\":\"pie\",\"meta\":{\"a\":1},\"items\":[{\"cite\":\"t1_nw\"}]"))
                .isNotNull();
    }

    /**
     * 补括号是<b>容错</b>,不是「想办法让它过」。真的坏掉的输出必须判死,
     * 否则我们会把一堆半截 JSON 当成图画出来 —— 那比不画更糟。
     */
    @Test
    @DisplayName("真的坏了就判死,不硬凑")
    void trulyBroken_returnsNull() {
        assertThat(AskCitationRenderer.parseChartJson("{\"type\":\"pie\",\"items\":[{\"lab")).isNull();
        assertThat(AskCitationRenderer.parseChartJson("这根本不是 JSON")).isNull();
        assertThat(AskCitationRenderer.parseChartJson("")).isNull();
    }
}
