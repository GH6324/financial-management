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
     * v1.19.13 · 百炼收系统提示词的字段名是 <b>{@code system}</b>,不是 {@code instructions}。
     *
     * <p>怎么发现的:创建明明成功了(HTTP 200 + 有 agent_id),但 {@code GET /agents/{id}} 回来的对象里
     * <b>{@code "system": null}</b>,而 {@code instructions} 这个键<b>根本不在响应里</b>。
     * 百炼对不认识的字段是<b>静默忽略</b>的 —— 于是线上那个 agent 挂上了 MCP、却<b>一句系统提示词都没有</b>:
     * 它能调工具,但不知道自己是谁、不知道「不许做数学 / 不许换算币种 / 拿不准先调 capabilities」这些口径纪律。</p>
     *
     * <p>这类错误<b>不报错、不降级、看起来完全成功</b>,所以下面 {@link #verifyTemplate} 会回读一次确认。</p>
     */
    private static final String PROMPT_FIELD = "system";

    private static final String AGENT_NAME = "家庭资产超级 Agent";

    /**
     * 创建与更新共用的请求体。
     *
     * <p>合成一份是必须的:百炼的更新是<b>全量替换</b>(缺省字段视为清空),
     * 而这两处原来各写一份 —— v1.19.11 的两个形状 bug 就是「改了一处漏了另一处」的同型风险。</p>
     *
     * <p>{@code mcp_servers} 里<b>只能写引用</b>({@code type} + {@code name}),
     * 不能内联 url 和 headers —— 这就是为什么用户必须先去控制台注册。</p>
     */
    private Map<String, Object> agentBody(String systemPrompt, String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", AGENT_NAME);
        // v1.19.11 · model 是**对象**不是字符串。百炼原话:
        //   Cannot construct instance of `DashModelConfigDTO` … from String value ('qwen-plus')
        //   (through reference chain: DashCreateAgentRequest["model"])
        body.put("model", Map.of("id", model == null || model.isBlank() ? configuredModel() : model));
        body.put(PROMPT_FIELD, systemPrompt);
        // v1.19.11 · type 是 **customer** 不是 custom。百炼直接给了合法值:
        //   mcpServers[0].type 取值非法: custom,合法值: [official, customer]
        body.put("mcp_servers", List.of(Map.of("type", "customer", "name", mcpServerId())));
        return body;
    }

    /** 创建 Agent */
    public String createAgent(String systemPrompt, String model) throws Exception {
        JsonNode n = post(agentBase() + "/agents", agentBody(systemPrompt, model));
        String id = firstText(n, "agent_id", "agentId", "id");
        if (id == null) throw new IllegalStateException("百炼没有返回 agent_id");
        String ver = firstText(n, "version", "agent_version");
        configService.set(FAMILY_ID, K_ASK_MA_AGENT_ID, id);
        configService.set(FAMILY_ID, K_ASK_MA_AGENT_VERSION, ver == null ? "1" : ver);
        // id 先落库再回读:万一回读这一步失败(网络/超时),agent 已经建出来了,
        // 下次点按钮要走「更新」而不是再建一个。
        verifyTemplate(id);
        return id;
    }

    /**
     * 更新 Agent 模板。
     *
     * <p>v1.19.13 · 动词是 <b>{@code POST /agents/{id}}</b>。原来写的 {@code PUT} 百炼直接回
     * <b>405 请求方法不支持</b>({@code PATCH} 也一样)—— 而这条 405 出现在用户已经创建成功之后,
     * 于是页面上写着「创建失败」,他以为整条路线还没通,其实 agent 早就建好了。</p>
     *
     * <p><b>全量替换</b>:缺省字段视为清空,所以每次都把 name/model/{@value #PROMPT_FIELD}/
     * mcp_servers 全带上 —— 少带一个就是把它删了,而且删得静悄悄。{@code version} 是必填(乐观锁),
     * 缺了百炼回「version 不能为空」。</p>
     *
     * <p>已存在的会话<b>锁定创建时的 version</b>,不受本次更新影响。</p>
     */
    public void updateAgent(String systemPrompt, String model) throws Exception {
        Map<String, Object> body = agentBody(systemPrompt, model);
        body.put("version", configService.getString(FAMILY_ID, K_ASK_MA_AGENT_VERSION, "1"));
        JsonNode n = post(agentBase() + "/agents/" + agentId(), body);
        String ver = firstText(n, "version", "agent_version");
        if (ver != null) configService.set(FAMILY_ID, K_ASK_MA_AGENT_VERSION, ver);
        verifyTemplate(agentId());
    }

    /**
     * v1.19.13 · 回读确认模板真的存住了。
     *
     * <p>加它的直接原因见 {@link #PROMPT_FIELD}:字段名发错时百炼<b>静默忽略</b>,
     * 创建返回 200、有 agent_id,一切看起来都成功 —— 而 agent 是个空壳。
     * <b>「上游收下了」不等于「上游存住了」</b>,凡是靠字段名约定的写入都得回读一次。</p>
     */
    private void verifyTemplate(String id) throws Exception {
        JsonNode a = get(agentBase() + "/agents/" + id);
        boolean hasPrompt = !a.path(PROMPT_FIELD).asText("").isBlank();
        boolean hasMcp = a.path("mcp_servers").isArray() && !a.path("mcp_servers").isEmpty();
        if (hasPrompt && hasMcp) return;
        throw new IllegalStateException(
                "百炼收下了(HTTP 200)但没存住:系统提示词" + (hasPrompt ? "在" : "是空的")
                + " · MCP 引用" + (hasMcp ? "在" : "是空的")
                + "。这通常意味着请求里的字段名和百炼当前的约定对不上 —— 它对不认识的字段是静默忽略的。");
    }

    /** 给用户去百炼控制台粘贴的 MCP 配置 —— 明文口令只在生成那一屏出现一次 */
    public String mcpConfigJson(String baseUrl, String plaintextToken) {
        // 逐个 put,不用 Map.of —— Map.of 不保证顺序,生成出来 headers 会跑到 type 前面。
        // 这段是给人复制粘贴的,字段顺序乱掉虽然不影响解析,但读起来像是随手拼的。
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "streamableHttp");
        entry.put("url", baseUrl + "/mcp");
        entry.put("headers", Map.of("Authorization", "Bearer " + plaintextToken));
        Map<String, Object> servers = new LinkedHashMap<>();
        servers.put("family-finance", entry);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("mcpServers", servers);
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(cfg);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ──────────────────────── 底层 ────────────────────────

    private JsonNode post(String url, Object body) throws Exception { return send("POST", url, body); }

    /** 回读用。不带 body —— 有些网关对带 body 的 GET 会直接拒 */
    private JsonNode get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey())
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) throw new UpstreamException(resp.statusCode(), resp.body());
        return json.readTree(resp.body());
    }

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

    /** v1.19.12 · 模型从配置读,不再写死 —— 子业务空间开通的往往不是默认那个 */
    private String configuredModel() {
        String m = configService.getString(FAMILY_ID, K_ASK_MA_MODEL, ASK_MA_MODEL_DEFAULT);
        return m == null || m.isBlank() ? ASK_MA_MODEL_DEFAULT : m.trim();
    }

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

    /**
     * 上游(百炼)返回的非 2xx。
     *
     * <p>v1.19.11 · <b>message 里必须带上百炼说了什么</b>。原来 {@code super("upstream " + status)}
     * 把 body 存进字段却不放进 message,而调用方用的正是 {@code getMessage()} ——
     * 于是用户只看到「upstream 400」,再配一句我们猜的「先确认业务空间 ID、MCP 服务 ID 都对」。</p>
     *
     * <p><b>代价是真实的</b>:2026-09-03 用户卡在创建 Agent,提示让他去查那两个 ID,
     * 而那两个 ID 本来就是对的;百炼其实明确说了 {@code model} 字段类型不对、
     * 以及 {@code mcpServers[0].type} 的合法值是什么。<b>最有用的一句话被我们丢掉了</b>,
     * 排查因此绕了一大圈。与 v1.19.4「识别失败,请重试」是同一类错误。</p>
     */
    private static final class UpstreamException extends RuntimeException {
        final int status;
        final String body;
        UpstreamException(int status, String body) {
            super("upstream " + status + (body == null || body.isBlank() ? "" : " · " + brief(body)));
            this.status = status;
            this.body = body;
        }
        /** 百炼的错误体是 JSON,里面那句 message 才是人能看懂的部分;取不出来就退回原文截断 */
        private static String brief(String body) {
            try {
                JsonNode n = new ObjectMapper().readTree(body);
                JsonNode m = n.path("error").path("message");
                if (m.isTextual() && !m.asText().isBlank()) return trim(m.asText());
                if (n.path("message").isTextual()) return trim(n.path("message").asText());
            } catch (Exception ignored) { }
            return trim(body);
        }
        private static String trim(String s) {
            String one = s.replaceAll("\\s+", " ").trim();
            return one.length() > 300 ? one.substring(0, 300) + "…" : one;
        }
    }
}
