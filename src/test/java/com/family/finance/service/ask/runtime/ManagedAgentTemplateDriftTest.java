package com.family.finance.service.ask.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「百炼上的 Agent 模板过期了没有」+「旧会话要不要换新」,穷举。
 *
 * <h3>这是生产事故换来的</h3>
 *
 * <p>2026-09-23:生产上的超级 Agent 对用户说「目前这个会话里没有任何工具连接到我这边」。
 * 查下来,百炼上那个 Agent 是 09-04 建的,而 09-09 发布的 v1.20.2 才修掉「只声明 mcp_servers、
 * 不启用 tools」—— <b>发新版本不会更新远端的 Agent</b>,于是它的 {@code tools} 一直是空数组,
 * 百炼一次都没来调我们的 MCP(nginx 日志 14 天零条)。</p>
 *
 * <p>下面两份 JSON 就是照着当时从百炼读回来的<b>真实结构</b>写的(生产那份 tools 为空,
 * beta 那份 tools 里是 mcp_toolkit)。浏览器在 beta 上造不出「模板过期」这个状态,
 * 所以判据只能落在这里。</p>
 */
class ManagedAgentTemplateDriftTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final List<String> OURS = List.of("capabilities", "summary", "pivot");

    private static JsonNode j(String s) throws Exception { return M.readTree(s); }

    @Test
    @DisplayName("生产那份:声明了 mcp_servers,但 tools 是空的 → 完全不能用")
    void prodShape_noTools() throws Exception {
        var d = ManagedAgentRuntime.driftOf(j("""
                {"mcp_servers":[{"type":"customer","name":"mcp-A","url":null}],"tools":[]}"""),
                "mcp-A", OURS);
        assertThat(d.checked()).isTrue();
        assertThat(d.noTools()).isTrue();
        assertThat(d.stale()).isTrue();
    }

    @Test
    @DisplayName("beta 那份:mcp_toolkit 把我们的工具逐个启用了 → 不过期")
    void betaShape_allEnabled() throws Exception {
        var d = ManagedAgentRuntime.driftOf(j("""
                {"mcp_servers":[{"type":"customer","name":"mcp-A"}],
                 "tools":[{"type":"mcp_toolkit","mcp_server_name":"mcp-A",
                           "default_config":{"enabled":true},
                           "configs":[{"name":"capabilities","enabled":true},
                                      {"name":"summary","enabled":true},
                                      {"name":"pivot","enabled":true}]}]}"""),
                "mcp-A", OURS);
        assertThat(d.stale()).isFalse();
        assertThat(d.missing()).isEmpty();
    }

    /** 新版本加了工具、模板没更新 —— 还能用,但少了能力。要指出来,不能当成全坏。 */
    @Test
    @DisplayName("新版本加了工具但模板没更新 → 过期,且说得出少了哪几个")
    void newToolNotInTemplate() throws Exception {
        var d = ManagedAgentRuntime.driftOf(j("""
                {"tools":[{"type":"mcp_toolkit","mcp_server_name":"mcp-A",
                           "configs":[{"name":"capabilities","enabled":true},{"name":"summary","enabled":true}]}]}"""),
                "mcp-A", OURS);
        assertThat(d.noTools()).isFalse();
        assertThat(d.stale()).isTrue();
        assertThat(d.missing()).containsExactly("pivot");
    }

    /** 同一个 agent 可能还挂着别的 MCP 服务;那些服务上的同名工具不算我们的。 */
    @Test
    @DisplayName("工具挂在别的 MCP 服务上 → 不算数")
    void toolsOnAnotherServerDontCount() throws Exception {
        var d = ManagedAgentRuntime.driftOf(j("""
                {"tools":[{"type":"mcp_toolkit","mcp_server_name":"mcp-OTHER",
                           "configs":[{"name":"capabilities"},{"name":"summary"},{"name":"pivot"}]}]}"""),
                "mcp-A", OURS);
        assertThat(d.noTools()).isTrue();
    }

    @Test
    @DisplayName("显式 enabled:false 的不算启用")
    void explicitlyDisabled() throws Exception {
        var d = ManagedAgentRuntime.driftOf(j("""
                {"tools":[{"type":"mcp_toolkit","mcp_server_name":"mcp-A",
                           "configs":[{"name":"capabilities","enabled":true},
                                      {"name":"summary","enabled":false},
                                      {"name":"pivot","enabled":true}]}]}"""),
                "mcp-A", OURS);
        assertThat(d.missing()).containsExactly("summary");
    }

    // ───────── 旧会话换新 ─────────

    @Test
    @DisplayName("会话版本与当前模板版本一致 → 复用")
    void sameVersionReused() {
        assertThat(ManagedAgentRuntime.sessionFor("sesn_01ABC@v3", "3")).isEqualTo("sesn_01ABC");
    }

    /**
     * 百炼的会话锁定创建时的模板版本。模板更新之后还复用旧会话,
     * 用户点完「更新」回到原来的对话一问,照样「没有工具」—— 只会以为更新没生效。
     */
    @Test
    @DisplayName("模板更新过 → 旧会话不再复用")
    void staleVersionDropped() {
        assertThat(ManagedAgentRuntime.sessionFor("sesn_01ABC@v2", "3")).isNull();
    }

    @Test
    @DisplayName("老格式(不带版本)→ 版本未知,新开一次")
    void legacyFormatDropped() {
        assertThat(ManagedAgentRuntime.sessionFor("sesn_01M34G4Y1KGBBECZM1X40FEDTV", "2")).isNull();
    }

    @Test
    @DisplayName("没有会话 → 新开")
    void blank() {
        assertThat(ManagedAgentRuntime.sessionFor(null, "2")).isNull();
        assertThat(ManagedAgentRuntime.sessionFor("", "2")).isNull();
    }
}
