package com.family.finance.service.ask;

import com.family.finance.service.ask.runtime.ManagedAgentRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.27 · 超级 Agent 提议记住(FR-854 / FR-849)· 托管每轮上下文(FR-853)· 更新 Agent 保留控制台 skill(FR-857)。
 */
class AskRememberParserTest {

    @Test
    void parsesThreeShapes() {
        var p = AskRememberParser.first("好的。\n{{remember:房子是自住的,别建议卖}}").orElseThrow();
        assertThat(p.text()).isEqualTo("房子是自住的,别建议卖");
        assertThat(p.suggestsTemplate()).isFalse();
        assertThat(p.suggestsExclude()).isFalse();

        var t = AskRememberParser.first("{{remember:以后都按保守的来|STEADY}}").orElseThrow();
        assertThat(t.templateKey()).isEqualTo("STEADY");
        assertThat(t.templateName()).isEqualTo("稳健守护");

        var e = AskRememberParser.first("{{remember:别算那套房子||exclude=自住房}}").orElseThrow();
        assertThat(e.excludeName()).isEqualTo("自住房");
        assertThat(AskRememberParser.first("{{remember:别算车||exclude}}").orElseThrow().excludeName()).isEmpty();
    }

    /** 模型输出是不可信输入:不认识的模板 key 丢掉;空原文不出卡 */
    @Test
    void rejectsUnknownTemplateAndEmptyText() {
        assertThat(AskRememberParser.first("{{remember:偏保守|HACKED}}").orElseThrow().templateKey()).isNull();
        assertThat(AskRememberParser.first("{{remember:   }}")).isEmpty();
        assertThat(AskRememberParser.first("没有标记")).isEmpty();
        String longText = "字".repeat(300);
        assertThat(AskRememberParser.first("{{remember:" + longText + "}}").orElseThrow().text()).hasSize(200);
    }

    @Test
    void rendererStripsMarkerFromProseAndCopy() {
        var r = new AskCitationRenderer(null);
        String body = "看了一下。\n{{remember:房子别卖}}";
        assertThat(r.plainText(body, List.of())).isEqualTo("看了一下。");
    }

    @Test
    void managedTurnInputCarriesContextOnlyWhenPresent() {
        assertThat(ManagedAgentRuntime.composeTurnInput(null, "问题")).isEqualTo("问题");
        String in = ManagedAgentRuntime.composeTurnInput("[分析上下文 · x]\n- 偏好", "问题");
        assertThat(in).startsWith(ManagedAgentRuntime.CONTEXT_MARK).endsWith("[用户这一轮的问题]\n问题");
    }

    /** FR-857 · 回读远端:skills 与别的工具带回去,我们自己的 MCP 工具以本地为准 */
    @Test
    void updateAgentKeepsRemoteSkillsAndForeignTools() throws Exception {
        var remote = new ObjectMapper().readTree("""
                {"id":"agent_1","created_at":"x","system":"旧提示词",
                 "skills":[{"name":"我的技能","id":"sk_1"}],
                 "tools":[{"type":"mcp_toolkit","mcp_server_name":"mcp-ours","configs":[{"name":"pivot"}]},
                          {"type":"builtin_toolkit","name":"web_search"},
                          {"type":"mcp_toolkit","mcp_server_name":"mcp-theirs"}]}
                """);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("system", "新提示词");
        body.put("tools", List.of(Map.of("type", "mcp_toolkit", "mcp_server_name", "mcp-ours")));
        ManagedAgentRuntime.mergeRemoteExtras(body, remote, "mcp-ours");
        assertThat(body.get("system")).isEqualTo("新提示词");
        assertThat(body).doesNotContainKey("id").doesNotContainKey("created_at");
        assertThat((List<?>) body.get("skills")).hasSize(1);
        @SuppressWarnings("unchecked") List<Map<String, Object>> tools = (List<Map<String, Object>>) body.get("tools");
        assertThat(tools).hasSize(3);
        assertThat(tools.stream().filter(t -> "mcp-ours".equals(t.get("mcp_server_name")))).hasSize(1);
        assertThat(tools).anyMatch(t -> "web_search".equals(t.get("name")));
        assertThat(tools).anyMatch(t -> "mcp-theirs".equals(t.get("mcp_server_name")));
    }
}
