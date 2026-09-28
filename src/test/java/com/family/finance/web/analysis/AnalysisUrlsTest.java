package com.family.finance.web.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** v1.27 · 站内链接与回跳白名单(FR-823 / FR-882) */
class AnalysisUrlsTest {

    @Test
    void withSkipsEmptyParams() {
        assertThat(AnalysisUrls.with("/checkup", null, null, null)).isEqualTo("/checkup");
        assertThat(AnalysisUrls.with("/checkup", "ALL", "custom:3", "checkup-ai"))
                .isEqualTo("/checkup?scope=ALL&tpl=custom:3#checkup-ai");
    }

    @Test
    void customizeEncodesBackUrl() {
        String u = AnalysisUrls.customize("STEADY", "/checkup?scope=ALL&tpl=GROWTH#checkup-ai");
        assertThat(u).startsWith("/admin/analysis/template/new?from=STEADY&back=")
                .doesNotContain("&tpl=")
                .contains("%26tpl%3DGROWTH%23checkup-ai");
    }

    /** 只收站内几页的相对路径 —— 不是开放重定向 */
    @Test
    void safeBackIsAWhitelist() {
        assertThat(AnalysisUrls.safeBack("/checkup?scope=ALL#checkup-ai")).isEqualTo("/checkup?scope=ALL#checkup-ai");
        assertThat(AnalysisUrls.safeBack("/reports#allocation-diff")).isNotNull();
        assertThat(AnalysisUrls.safeBack("/ask")).isEqualTo("/ask");
        assertThat(AnalysisUrls.safeBack("https://evil.example")).isNull();
        assertThat(AnalysisUrls.safeBack("//evil.example/checkup")).isNull();
        assertThat(AnalysisUrls.safeBack("/checkupx")).isNull();
        assertThat(AnalysisUrls.safeBack("/admin/members")).isNull();
    }

    @Test
    void withTemplateReplacesTpl() {
        assertThat(AnalysisUrls.withTemplate("/checkup?scope=ALL&tpl=GROWTH#checkup-ai", "custom:9"))
                .isEqualTo("/checkup?scope=ALL&tpl=custom:9#checkup-ai");
        assertThat(AnalysisUrls.withTemplate("https://x", "custom:9")).isNull();
    }
}
