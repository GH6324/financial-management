package com.family.finance.service.ask.runtime;

import com.family.finance.service.ask.AskToolRegistry;
import com.family.finance.service.checkup.llm.LlmCatalog;
import com.family.finance.service.config.FamilyConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.family.finance.service.config.FamilyConfigService.*;

/**
 * v1.19 · 百炼 Managed Agents 运行时(选型一的选定方案,tech-design 附录·选型二)。
 *
 * <p>Agent 跑在百炼那边,取数时<b>回调</b>本实例的 {@code /mcp}。换来的是服务端 session 持久化、
 * 中断续接、多步工具编排 —— 这些是维护者点名要的能力,自己写 loop 给不了。</p>
 *
 * <h3>前置条件(硬性,不是建议)</h3>
 * <ol>
 *   <li>本实例<b>公网可达且 HTTPS</b> —— 百炼要连得上 {@code /mcp};</li>
 *   <li>用户已在百炼控制台<b>手工注册</b>自定义 MCP 服务并拿到服务 ID ——
 *       {@code mcp_servers} 只能<b>引用已注册服务</b>,而注册<b>没有公开 API</b>。
 *       这是依赖方的结构限制,不是我们能省掉的步骤。</li>
 * </ol>
 *
 * <p><b>本类的云端往返尚未在真实环境跑通</b>:beta 只有 IP、没有域名和证书,百炼回调不到。
 * 代码按已查证的接口形态实现,但「实际跑通」这件事必须等一个有公网 HTTPS 的环境 ——
 * 在那之前不要在任何地方把它描述成已验证。没有公网的部署走 {@link LocalToolLoopRuntime}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManagedAgentRuntime implements AgentRuntime {

    public static final String CODE = "managed";

    private static final long FAMILY_ID = 1L;
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(180);

    private final FamilyConfigService configService;
    private final AskToolRegistry registry;
    private final ObjectMapper json = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override public String code() { return CODE; }

    @Override public String label() { return "百炼托管 Agent(需要公网 HTTPS)"; }

    @Override
    public boolean available(long familyId) {
        return unavailableReason(familyId) == null;
    }

    @Override
    public String unavailableReason(long familyId) {
        if (apiKey().isBlank()) return "还没有配百炼的 API Key。去「数据源接入」页填一个。";
        if (workspace().isBlank()) return "还没填百炼的业务空间 ID。在「AI 接入」页填一下。";
        String base = configService.getString(FAMILY_ID, K_ASK_PUBLIC_BASE_URL, "");
        if (base.isBlank()) {
            return "还没填本站的公网地址。托管 Agent 跑在百炼那边,要能回头访问你这台机器才取得到数。";
        }
        if (!base.startsWith("https://")) {
            return "本站公网地址必须是 https —— 百炼不接受 http 回调。没有证书的话,用「本机直连」那条路线。";
        }
        if (mcpServerId().isBlank()) {
            return "还差最后一步:去百炼控制台把 MCP 服务注册好,把服务 ID 填回来。"
                 + "(这一步百炼没开放接口,只能手工)";
        }
        if (agentId().isBlank()) return "还没有创建 Agent。在「AI 接入」页点一下「创建 Agent」。";
        return null;
    }

    // ──────────────────────── 会话 ────────────────────────

    @Override
    public void run(AskTurn turn, AskSink sink) {
        String reason = unavailableReason(turn.familyId());
        if (reason != null) { sink.failed(reason); return; }

        try {
            String sessionId = turn.providerRef();
            if (sessionId == null || sessionId.isBlank()) {
                sessionId = createSession();
                turn.onProviderRef().accept(sessionId);
            }
            streamEvents(sessionId, turn.question(), sink);
        } catch (Exception e) {
            log.warn("超级 Agent · 托管 agent 失败:{}", e.toString());
            sink.failed(humanError(e));
        }
    }

    private String createSession() throws Exception {
        JsonNode n = post(agentBase() + "/sessions", Map.of("agent_id", agentId()));
        String id = firstText(n, "session_id", "sessionId", "id");
        if (id == null) throw new IllegalStateException("百炼没有返回 session_id");
        return id;
    }

    /**
     * 提问并消费事件流。
     *
     * <p>事件里的工具调用是<b>百炼那边发起的</b>(它直连我们的 {@code /mcp}),
     * 我们只是从流里看到「它调了什么」,好把进度显示给用户。所以这里没有 dispatcher ——
     * 工具已经在 {@code /mcp} 那条路径上执行过,连同鉴权和审计。</p>
     */
    private void streamEvents(String sessionId, String question, AskSink sink) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agent_id", agentId());
        body.put("stream", true);
        body.put("input", Map.of("role", "user", "content", question));

        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(agentBase() + "/sessions/" + sessionId + "/events"))
                .timeout(READ_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey())
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<java.io.InputStream> resp =
                http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() >= 400) {
            throw new UpstreamException(resp.statusCode(),
                    new String(resp.body().readAllBytes(), StandardCharsets.UTF_8));
        }

        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (sink.cancelled()) { sink.stopped(); return; }
                if (!line.startsWith("data:")) continue;
                String payload = line.substring(5).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) continue;

                JsonNode n = json.readTree(payload);
                String type = firstText(n, "type", "event", "object");
                if (type == null) continue;

                if (type.contains("tool")) {
                    String tool = firstText(n.path("tool_call"), "name", "tool_name");
                    if (tool == null) tool = firstText(n, "name", "tool_name");
                    if (tool != null) {
                        String label = registry.displayName(tool);
                        if (type.contains("done") || type.contains("completed") || type.contains("result")) {
                            sink.toolDone(tool, label, 0, true, null, Map.of());
                        } else {
                            sink.toolStart(tool, label, null);
                        }
                    }
                } else if (type.contains("delta") || type.contains("output_text")) {
                    String piece = firstText(n, "delta", "text", "content");
                    if (piece != null && !piece.isEmpty()) sink.textDelta(piece);
                } else if (type.contains("error")) {
                    sink.failed("百炼那边报了个错:" + firstText(n, "message", "error"));
                    return;
                }
            }
        }
        sink.done();
    }

    // ──────────────────────── Agent 生命周期(管理页触发) ────────────────────────

    /**
     * 创建 Agent。
     *
     * <p>{@code mcp_servers} 里<b>只能写引用</b>({@code type} + {@code name}),
     * 不能内联 url 和 headers —— 试过,百炼会拒。这就是为什么用户必须先去控制台注册。</p>
     */
    public String createAgent(String systemPrompt, String model) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "家庭资产超级 Agent");
        body.put("model", model == null || model.isBlank() ? "qwen-plus" : model);
        body.put("instructions", systemPrompt);
        body.put("mcp_servers", List.of(Map.of("type", "custom", "name", mcpServerId())));
        JsonNode n = post(agentBase() + "/agents", body);
        String id = firstText(n, "agent_id", "agentId", "id");
        if (id == null) throw new IllegalStateException("百炼没有返回 agent_id");
        String ver = firstText(n, "version", "agent_version");
        configService.set(FAMILY_ID, K_ASK_MA_AGENT_ID, id);
        configService.set(FAMILY_ID, K_ASK_MA_AGENT_VERSION, ver == null ? "1" : ver);
        return id;
    }

    /**
     * 更新 Agent 模板。
     *
     * <p><b>PUT 是全量替换</b>:缺省字段视为清空。所以这里每次都把 name/model/instructions/
     * mcp_servers 全带上 —— 少带一个就是把它删了,而且删得静悄悄,下次提问才发现工具没了。</p>
     *
     * <p>已存在的会话<b>锁定创建时的 version</b>,不受本次更新影响。</p>
     */
    public void updateAgent(String systemPrompt, String model) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "家庭资产超级 Agent");
        body.put("model", model == null || model.isBlank() ? "qwen-plus" : model);
        body.put("instructions", systemPrompt);
        body.put("mcp_servers", List.of(Map.of("type", "custom", "name", mcpServerId())));
        body.put("version", configService.getString(FAMILY_ID, K_ASK_MA_AGENT_VERSION, "1"));
        JsonNode n = put(agentBase() + "/agents/" + agentId(), body);
        String ver = firstText(n, "version", "agent_version");
        if (ver != null) configService.set(FAMILY_ID, K_ASK_MA_AGENT_VERSION, ver);
    }

    /** 给用户去百炼控制台粘贴的 MCP 配置 —— 明文口令只在生成那一屏出现一次 */
    public String mcpConfigJson(String baseUrl, String plaintextToken) {
        Map<String, Object> cfg = Map.of("mcpServers", Map.of(
                "family-finance", new LinkedHashMap<>(Map.of(
                        "type", "streamableHttp",
                        "url", baseUrl + "/mcp",
                        "headers", Map.of("Authorization", "Bearer " + plaintextToken)))));
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(cfg);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ──────────────────────── 底层 ────────────────────────

    private JsonNode post(String url, Object body) throws Exception { return send("POST", url, body); }
    private JsonNode put(String url, Object body) throws Exception { return send("PUT", url, body); }

    private JsonNode send(String method, String url, Object body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey())
                .method(method, HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) throw new UpstreamException(resp.statusCode(), resp.body());
        return json.readTree(resp.body());
    }

    /**
     * 从几个候选字段名里取第一个有值的。
     *
     * <p>不是偷懒 —— 百炼这套接口还在演进(Assistant API 已经「下线中」),
     * 字段名在文档和实际返回之间出现过不一致。钉死一个名字的代价是接口一变就静默返回 null,
     * 而那会表现成「会话创建成功但 id 是空的」这种很难查的样子。</p>
     */
    private static String firstText(JsonNode n, String... names) {
        if (n == null || n.isMissingNode()) return null;
        for (String name : names) {
            JsonNode v = n.get(name);
            if (v != null && !v.isNull() && v.isValueNode()) {
                String s = v.asText();
                if (!s.isBlank()) return s;
            }
        }
        return null;
    }

    private String apiKey() {
        return configService.getString(FAMILY_ID, LlmCatalog.DASHSCOPE.keyName(), "");
    }
    private String workspace() { return configService.getString(FAMILY_ID, K_ASK_MA_WORKSPACE, ""); }
    private String mcpServerId() { return configService.getString(FAMILY_ID, K_ASK_MA_MCP_SERVER, ""); }
    private String agentId() { return configService.getString(FAMILY_ID, K_ASK_MA_AGENT_ID, ""); }

    /** 业务空间是子域,不是路径参数 */
    private String agentBase() {
        return "https://" + workspace() + ".cn-beijing.maas.aliyuncs.com/api/v1/agentstudio";
    }

    private String humanError(Exception e) {
        if (e instanceof UpstreamException u) {
            if (u.status == 401 || u.status == 403) return "百炼那边说凭据不对或者没权限。检查 API Key 和业务空间 ID。";
            if (u.status == 404) return "百炼那边找不到这个 Agent。可能被删了 —— 在「AI 接入」页重新创建一个。";
            if (u.status == 429) return "问得太频繁,百炼限流了。等一会儿再问。";
            return "百炼返回了错误(" + u.status + ")。稍后再试试。";
        }
        if (e instanceof java.net.http.HttpTimeoutException) {
            return "等百炼回话超时了。这个问题可能有点大,拆小一点再问试试。";
        }
        return "连不上百炼。检查一下服务器能不能出网。";
    }

    private static final class UpstreamException extends RuntimeException {
        final int status;
        final String body;
        UpstreamException(int status, String body) {
            super("upstream " + status);
            this.status = status;
            this.body = body;
        }
    }
}
