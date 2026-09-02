package com.family.finance.web.ask;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.19.9 · MCP 版本协商 —— 规范里这条是 MUST,而我们违反过。
 *
 * <p>规范(basic/lifecycle · Version Negotiation):
 * 「If the server supports the requested protocol version, it <b>MUST</b> respond with
 * the same version. Otherwise, the server <b>MUST</b> respond with another protocol
 * version it supports.」</p>
 *
 * <p>原实现无条件回 {@code 2025-06-18}。线上后果:百炼请求较早的版本,收到它不认,
 * 直接 {@code -32602 Unsupported protocol version from the server: 2025-06-18} ——
 * <b>握手就断,连工具列表都拿不到</b>,整条托管接入路线不可用。</p>
 *
 * <p>用反射调私有方法:这条规则的价值在于「给什么就回什么」这个映射本身,
 * 为它把方法改成 public 反而扩大了这个类的对外面(它是唯一的入站面)。</p>
 */
class McpProtocolVersionTest {

    @SuppressWarnings("unchecked")
    private static String negotiate(String clientVersion) throws Exception {
        Method m = McpEndpoint.class.getDeclaredMethod("initializeResult", Map.class);
        m.setAccessible(true);
        Map<String, Object> body = clientVersion == null
                ? Map.of("method", "initialize")
                : Map.of("method", "initialize", "params", Map.of("protocolVersion", clientVersion));
        McpEndpoint ep = new McpEndpoint(null, null, null);
        return String.valueOf(((Map<String, Object>) m.invoke(ep, body)).get("protocolVersion"));
    }

    @Test
    void supportedVersion_isEchoedBack_exactly() throws Exception {
        // MUST:支持的版本必须原样回显 —— 这正是线上失败的那一条
        for (String v : List.of("2025-06-18", "2025-03-26", "2024-11-05")) {
            assertThat(negotiate(v)).as("客户端要 %s 就必须回 %s", v, v).isEqualTo(v);
        }
    }

    @Test
    void bailianCase_earlierVersion_notForcedToLatest() throws Exception {
        // 百炼那次:它要 2024-11-05,我们回 2025-06-18 → 它报 -32602 断开
        assertThat(negotiate("2024-11-05")).isNotEqualTo("2025-06-18");
        assertThat(negotiate("2024-11-05")).isEqualTo("2024-11-05");
    }

    @Test
    void unsupportedOrMissing_fallsBackToLatestSupported() throws Exception {
        // 讲不了的版本 → 回我们支持的最新,由客户端决定要不要继续(这也是规范说的)
        assertThat(negotiate("1.0.0")).isEqualTo("2025-06-18");
        assertThat(negotiate("2099-01-01")).isEqualTo("2025-06-18");
        assertThat(negotiate(null)).isEqualTo("2025-06-18");
    }

    @Test
    void latestIsFirstInTheSupportedList() throws Exception {
        // 列表按新→旧排;回退值取第一个。顺序写反的话「不支持时回最新」会变成回最旧。
        var f = McpEndpoint.class.getDeclaredField("SUPPORTED_PROTOCOL_VERSIONS");
        f.setAccessible(true);
        @SuppressWarnings("unchecked") List<String> vs = (List<String>) f.get(null);
        assertThat(vs).isSortedAccordingTo((a, b) -> b.compareTo(a));
        assertThat(vs.get(0)).isEqualTo("2025-06-18");
    }
}
