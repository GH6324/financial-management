package com.family.finance.service.ask.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「本站公网地址」归一化,穷举。
 *
 * <h3>这条判据是生产事故换来的</h3>
 *
 * <p>页面上那一格问的是<b>站点根</b>,而我们生成给人粘贴的是一条完整的
 * {@code https://域名/mcp}。于是很自然地,那条完整地址又被填回了这一格 ——
 * 拼出来就是 {@code /mcp/mcp}。</p>
 *
 * <p>2026-09-23 在生产上的表现:超级 Agent 一本正经地回答
 * 「目前这个会话里没有任何工具连接到我这边」。因为 {@code /mcp/mcp} 不是我们的端点,
 * 它被普通 Web 安全链接管,回 <b>302 跳登录页</b>;百炼拿到一个 HTML 跳转,
 * 得出「这个服务器没有工具」。</p>
 *
 * <p><b>最难受的是我们这边一条记录都没有</b> —— 请求根本没走到鉴权那一步,
 * 所以入站审计表里连一条失败都看不到,只能看出「百炼从某天起就不来了」。</p>
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
