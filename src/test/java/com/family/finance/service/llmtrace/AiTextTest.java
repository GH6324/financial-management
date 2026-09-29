package com.family.finance.service.llmtrace;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** v1.28 FR-913 · 隐私模式糊 AI 正文里的金额(护栏 v128-AI-TEXT-PRIV) */
class AiTextTest {

    @Test
    void 金额包进data_priv_先转义() {
        String html = AiText.privHtml("家庭净资产 ¥6258986,<b>建议</b>留 3 万元应急,收益 +4.12%,储备 8.5 个月");
        assertThat(html).contains("<span data-priv>¥6258986</span>")
                .contains("<span data-priv>3 万元</span>")
                .contains("&lt;b&gt;建议&lt;/b&gt;")
                .doesNotContain("<span data-priv>+4.12%")
                .doesNotContain("<span data-priv>8.5");
    }

    @Test
    void 各种货币写法() {
        assertThat(AiText.privHtml("US$1,200.50")).isEqualTo("<span data-priv>US$1,200.50</span>");
        assertThat(AiText.privHtml("HK$ 300k")).isEqualTo("<span data-priv>HK$ 300k</span>");
        assertThat(AiText.privHtml("约 1.2 亿")).isEqualTo("约 <span data-priv>1.2 亿</span>");
        assertThat(AiText.privHtml(null)).isEmpty();
    }

    /** 前端 privText 与服务端用的是同一个字符串字面量 —— 两边切出来的金额不会不一样 */
    @Test
    void 前端正则与服务端逐字相同() throws Exception {
        String js = Files.readString(Path.of("src/main/resources/static/js/prompt-peek.js"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("var MONEY_REGEX = \"(.*?)\";").matcher(js);
        assertThat(m.find()).as("prompt-peek.js 里找不到 MONEY_REGEX").isTrue();
        String javaSrc = Files.readString(Path.of("src/main/java/com/family/finance/service/llmtrace/AiText.java"), StandardCharsets.UTF_8);
        Matcher j = Pattern.compile("MONEY_REGEX =\\s*\"(.*?)\"\\s*\\+\\s*\"(.*?)\";", Pattern.DOTALL).matcher(javaSrc);
        assertThat(j.find()).isTrue();
        assertThat(m.group(1)).isEqualTo(j.group(1) + j.group(2));
    }
}
