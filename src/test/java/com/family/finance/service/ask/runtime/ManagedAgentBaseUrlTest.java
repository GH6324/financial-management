package com.family.finance.service.ask.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「本站公网地址」归一化,穷举。
 *
 * <p>页面上那一格问的是<b>站点根</b>,而我们生成给人粘贴的是完整的 {@code https://域名/mcp}。
 * 那条完整地址很容易被填回这一格,于是展示出来的配置变成 {@code /mcp/mcp} ——
 * 那个路径不是我们的端点,被普通 Web 安全链接管,回 302 跳登录页。</p>
 *
 * <p>注意:2026-09-23 生产上超级 Agent「没有工具」<b>不是</b>这个原因(一开始被误判成了它),
 * 真因是百炼上的 Agent 模板停在旧版本、tools 为空,见 {@code ManagedAgentTemplateDriftTest}。
 * 这里修的是一个真实存在、但当时没有被触发的潜在 bug。</p>
 */
class ManagedAgentBaseUrlTest {

    @Test
    @DisplayName("正常的站点根原样保留")
    void plainRoot() {
        assertThat(ManagedAgentRuntime.normalizeBaseUrl("https://dixi-token.top"))
                .isEqualTo("https://dixi-token.top");
    }

    @Test
    @DisplayName("末尾斜杠去掉")
    void trailingSlash() {
        assertThat(ManagedAgentRuntime.normalizeBaseUrl("https://dixi-token.top/"))
                .isEqualTo("https://dixi-token.top");
    }

    @Test
    @DisplayName("把完整的 MCP 地址填回来 → 吃掉那截 /mcp(生产上就是这么坏的)")
    void fullMcpUrlPastedBack() {
        assertThat(ManagedAgentRuntime.normalizeBaseUrl("https://dixi-token.top/mcp"))
                .isEqualTo("https://dixi-token.top");
        assertThat(ManagedAgentRuntime.normalizeBaseUrl("https://dixi-token.top/mcp/"))
                .isEqualTo("https://dixi-token.top");
    }

    @Test
    @DisplayName("粘贴了两次也认")
    void doublePasted() {
        assertThat(ManagedAgentRuntime.normalizeBaseUrl("https://dixi-token.top/mcp/mcp"))
                .isEqualTo("https://dixi-token.top");
    }

    /**
     * 只吃<b>末尾</b>那截。域名里带 mcp、或者部署在 /mcp-gateway 这种子路径下的,不许动 ——
     * 归一化的边界必须清楚,否则它会从「修错别字」变成「改坏别人的正确配置」。
     */
    @Test
    @DisplayName("只吃末尾的 /mcp,不碰域名里的 mcp、也不碰别的子路径")
    void onlyTrailingSegment() {
        assertThat(ManagedAgentRuntime.normalizeBaseUrl("https://mcp.example.com"))
                .isEqualTo("https://mcp.example.com");
        assertThat(ManagedAgentRuntime.normalizeBaseUrl("https://x.com/mcp-gateway"))
                .isEqualTo("https://x.com/mcp-gateway");
        assertThat(ManagedAgentRuntime.normalizeBaseUrl("https://x.com/app"))
                .isEqualTo("https://x.com/app");
    }

    @Test
    @DisplayName("空与 null 不抛")
    void blankIsSafe() {
        assertThat(ManagedAgentRuntime.normalizeBaseUrl(null)).isEmpty();
        assertThat(ManagedAgentRuntime.normalizeBaseUrl("   ")).isEmpty();
    }
}
