package com.family.finance.service.ask;

import com.family.finance.domain.ask.AskCitation;
import com.family.finance.domain.period.Period;
import com.family.finance.repository.PeriodMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.19 · 引用渲染。
 *
 * <p>这个类同时是<b>安全边界</b>:模型输出是不可信输入(提示词注入可以从账户名里来),
 * 而渲染结果是直接 {@code th:utext} 进页面的 HTML。所以转义那几条不是锦上添花。</p>
 */
class AskCitationRendererTest {

    /** 账期 7 → 2026-08,其余不存在。渲染只用得到 findById,mock 掉即可 */
    private final PeriodMapper periods = mock(PeriodMapper.class);

    private final AskCitationRenderer renderer = new AskCitationRenderer(periods);

    AskCitationRendererTest() {
        when(periods.findById(anyLong())).thenAnswer(inv -> {
            long id = inv.getArgument(0);
            if (id != 7L) return Optional.empty();
            Period p = new Period();
            p.setId(7L);
            p.setPeriodStart(LocalDate.of(2026, 8, 1));
            return Optional.of(p);
        });
    }

    private static AskCitation cite(String key, String metricKey, String value,
                                    Long periodId, boolean inProgress) {
        return AskCitation.builder()
                .citeKey(key).metricKey(metricKey).valueText(value)
                .periodId(periodId).inProgress(inProgress).build();
    }

    @Test
    @DisplayName("独占一行的标记 → 引用卡,含指标名/数值/账期/关账状态")
    void standaloneMarkerBecomesCard() {
        String html = renderer.renderHtml(
                "你的钱主要在这里:\n{{cite:c1}}",
                List.of(cite("c1", "kpi.netWorth", "7605199.80", 7L, true)));

        assertThat(html).contains("ask-cite");
        assertThat(html).contains("净资产");            // metricKey → 中文名
        assertThat(html).contains("7605199.80");
        assertThat(html).contains("2026-08");           // periodId → 账期
        assertThat(html).contains("未关账");             // inProgress 必须显式出现
        assertThat(html).contains("/dashboard");        // 点得回去
    }

    @Test
    @DisplayName("句中残留的标记退成行内 chip,不在句子中间插一张卡")
    void inlineMarkerBecomesChip() {
        String html = renderer.renderHtml(
                "净资产是 {{cite:c1}} 左右。",
                List.of(cite("c1", "kpi.netWorth", "7605199.80", null, false)));

        assertThat(html).contains("ask-chip");
        assertThat(html).doesNotContain("ask-cite-top");
        assertThat(html).contains("净资产是");
    }

    @Test
    @DisplayName("找不到对应引用的标记直接消失 —— 不能把 {{cite:xx}} 原样显示给用户")
    void unknownMarkerDisappears() {
        String html = renderer.renderHtml("这里有个数 {{cite:zz}} 。", List.of());
        assertThat(html).doesNotContain("cite:zz");
        assertThat(html).doesNotContain("{{");
    }

    @Test
    @DisplayName("模型输出里的 HTML 被转义 —— 提示词注入可以从账户名进来")
    void modelOutputIsEscaped() {
        String html = renderer.renderHtml(
                "账户叫 <img src=x onerror=alert(1)> 这个名字。", List.of());
        assertThat(html).doesNotContain("<img");
        assertThat(html).contains("&lt;img");
    }

    @Test
    @DisplayName("引用块里的值也转义 —— 数值来自工具,但 label 可能含账户名")
    void citationFieldsAreEscaped() {
        AskCitation c = cite("c1", "lens.pivot", "1<script>", null, false);
        String html = renderer.renderHtml("{{cite:c1}}", List.of(c));
        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("**粗体** 认,别的 markdown 原样留着(不引解析器)")
    void minimalMarkdown() {
        String html = renderer.renderHtml("**大头**在支付宝", List.of());
        assertThat(html).contains("<strong>大头</strong>");
    }

    @Test
    @DisplayName("裸金额会被认出来 —— 模型没按规矩用引用块时要能标出来")
    void bareNumberDetected() {
        assertThat(renderer.hasBareNumber("净资产是 1,234,567.89 元")).isTrue();
        assertThat(renderer.hasBareNumber("净资产是 1234567 元")).isTrue();
    }

    @Test
    @DisplayName("引用标记里的数字不算裸数字,年份和小整数也不算")
    void citedAndSmallNumbersAreNotBare() {
        assertThat(renderer.hasBareNumber("净资产是 {{cite:c1}}")).isFalse();
        assertThat(renderer.hasBareNumber("2026 年 8 月,一共 3 个平台")).isFalse();
    }

    @Test
    @DisplayName("没登记过的 metricKey 也要渲染出来,不能整块消失")
    void unknownMetricKeyStillRenders() {
        String html = renderer.renderHtml("{{cite:c1}}",
                List.of(cite("c1", "some.new.metric", "42", null, false)));
        assertThat(html).contains("ask-cite");
        assertThat(html).contains("42");
    }

}
