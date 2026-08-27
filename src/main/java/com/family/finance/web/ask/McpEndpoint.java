package com.family.finance.web.ask;

import com.family.finance.domain.ask.AskAuditResult;
import com.family.finance.domain.ask.AskScope;
import com.family.finance.service.ask.AskAccessGuard;
import com.family.finance.service.ask.AskTool;
import com.family.finance.service.ask.AskToolDispatcher;
import com.family.finance.service.ask.AskToolRegistry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.19 · MCP 端点(Streamable HTTP · JSON-RPC 2.0)。
 *
 * <p>这是本项目<b>唯一的入站面</b>,背后是一个家庭的全部资产数据。所以:</p>
 * <ul>
 *   <li><b>只读</b> —— 本类只暴露 {@code tools/call},而所有工具实现都不含写方法。
 *       这是物理保证,不是 prompt 约束。</li>
 *   <li><b>未通过一律 404</b>(scope 不足 403、限流 429)——
 *       {@code 401} 会告诉扫描者「这里有东西」。</li>
 *   <li><b>凭据只从 {@code Authorization} 头取</b> —— MCP 规范<b>禁止</b>在 URI query 里传
 *       bearer token,而且放 URL 必然进访问日志。</li>
 * </ul>
 *
 * <p>只实现 agent 真正会用到的几个方法:{@code initialize} / {@code tools/list} /
 * {@code tools/call} / {@code ping}。不做资源、提示词、采样等我们用不到的部分 ——
 * 少一个方法就少一个要守的面。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class McpEndpoint {

    /** 与百炼对齐的协议版本;客户端声明别的版本时我们照回自己的,由它决定要不要继续 */
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final AskAccessGuard guard;
    private final AskToolRegistry registry;
    private final AskToolDispatcher dispatcher;

    @PostMapping(value = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> handle(@RequestBody(required = false) Map<String, Object> body,
                                    HttpServletRequest req) {
        Object id = body == null ? null : body.get("id");
        String method = body == null ? null : String.valueOf(body.get("method"));

        // ── 鉴权 ── 需要的 scope 取决于具体工具;这里先按最低要求过一遍
        AskAccessGuard.Pass pass = guard.check(req, AskScope.AGGREGATE, method);
        if (!pass.ok()) return denied(pass.result());

        if (method == null || "null".equals(method)) {
            return ResponseEntity.ok(rpcError(id, -32600, "缺少 method"));
        }

        return switch (method) {
            case "initialize" -> ResponseEntity.ok(rpcResult(id, initializeResult()));
            case "notifications/initialized" -> ResponseEntity.noContent().build();
            case "ping" -> ResponseEntity.ok(rpcResult(id, Map.of()));
            case "tools/list" -> ResponseEntity.ok(rpcResult(id,
                    Map.of("tools", registry.mcpToolList())));
            case "tools/call" -> ResponseEntity.ok(callTool(id, body, req, pass));
            default -> ResponseEntity.ok(rpcError(id, -32601, "不支持的方法:" + method));
        };
    }

    // ──────────────────────── tools/call ────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> callTool(Object id, Map<String, Object> body,
                                         HttpServletRequest req, AskAccessGuard.Pass pass) {
        Object rawParams = body.get("params");
        if (!(rawParams instanceof Map<?, ?> params)) {
            return rpcError(id, -32602, "params 缺失");
        }
        String toolName = String.valueOf(params.get("name"));
        Map<String, Object> args = params.get("arguments") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();

        AskTool tool = registry.find(toolName).orElse(null);
        if (tool == null) {
            return rpcError(id, -32602, "没有这个工具:" + toolName);
        }
        // 逐工具再校一次 scope —— 入口那次只保证了最低门槛
        if (!pass.scope().covers(tool.requiredScope())) {
            return toolError(id, "这个工具需要「" + tool.requiredScope().getLabel()
                    + "」范围的凭据。请告诉用户去「AI 接入」把这个接入点的范围调高,或者换个问法。");
        }

        var result = dispatcher.call(pass.familyId(), toolName, args, pass.scope());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", result.ok());
        if (result.ok()) {
            payload.put("data", result.data());
            payload.put("meta", result.meta());
            if (!result.citations().isEmpty()) payload.put("citations", result.citations());
        } else {
            payload.put("error", result.error());
            if (result.meta().containsKey("allowed")) payload.put("allowed", result.meta().get("allowed"));
        }
        return rpcResult(id, Map.of(
                "content", List.of(Map.of("type", "text", "text", toJson(payload))),
                "isError", !result.ok()));
    }

    // ──────────────────────── 协议件 ────────────────────────

    private Map<String, Object> initializeResult() {
        return Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of("tools", Map.of("listChanged", false)),
                "serverInfo", Map.of("name", "family-finance-ask", "version", "1.19.0"),
                "instructions",
                        "这是一个家庭资产账房的【只读】接口。所有数字都是它已经算好的口径,"
                      + "你直接引用即可,不要自己做加减,也不要换算币种。"
                      + "拿不准维度或取值就先调 capabilities,不要猜。");
    }

    /**
     * 未通过时的响应。
     *
     * <p>除 scope(403)与限流(429)外<b>一律 404 且空体</b> ——
     * 不透露「功能存不存在」「凭据对不对」「是不是过期了」中的任何一条。</p>
     */
    private ResponseEntity<?> denied(AskAuditResult r) {
        if (r == AskAuditResult.SCOPE) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "scope_required"));
        }
        if (r == AskAuditResult.RATE) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "30")
                    .body(Map.of("error", "rate_limited", "retryAfter", 30));
        }
        return ResponseEntity.notFound().build();
    }

    private static Map<String, Object> rpcResult(Object id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    private static Map<String, Object> rpcError(Object id, int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", Map.of("code", code, "message", message));
        return m;
    }

    /** 工具级错误走 result + isError,而不是 JSON-RPC error —— 这样模型能读到并自我修正 */
    private static Map<String, Object> toolError(Object id, String message) {
        return rpcResult(id, Map.of(
                "content", List.of(Map.of("type", "text", "text", message)),
                "isError", true));
    }

    private String toJson(Object o) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"结果序列化失败\"}";
        }
    }
}
