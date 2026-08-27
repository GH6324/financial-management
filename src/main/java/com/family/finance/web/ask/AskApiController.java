package com.family.finance.web.ask;

import com.family.finance.domain.ask.AskAuditResult;
import com.family.finance.domain.ask.AskScope;
import com.family.finance.repository.AskUnmetNeedMapper;
import com.family.finance.service.ask.AskAccessGuard;
import com.family.finance.service.ask.AskToolDispatcher;
import com.family.finance.service.ask.AskToolRegistry;
import com.family.finance.service.ask.AskToolResult;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v1.19 · 只读 HTTP 接口。
 *
 * <p>与 MCP 端点<b>同一套工具、同一套凭据、同一套审计</b> —— 只是换了个协议。
 * 存在的理由是让用户能直接 curl 验证「AI 看到的数和页面上的一样」,
 * 以及将来接非 MCP 的客户端。</p>
 *
 * <p><b>整个包里没有任何写方法</b>(唯一的 POST 是 {@code /unmet},它只记一条
 * 「agent 说它够不着」的反馈,不碰账目)。这是物理保证,有静态护栏钉住。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ask")
@RequiredArgsConstructor
public class AskApiController {

    private final AskAccessGuard guard;
    private final AskToolRegistry registry;
    private final AskToolDispatcher dispatcher;
    private final AskUnmetNeedMapper unmetMapper;

    /** 工具清单(与 MCP 的 tools/list 同源) */
    @GetMapping(value = "/tools", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> tools(HttpServletRequest req) {
        var pass = guard.check(req, AskScope.AGGREGATE, "tools");
        if (!pass.ok()) return denied(pass.result());
        return ResponseEntity.ok(Map.of("tools", registry.mcpToolList()));
    }

    @GetMapping(value = "/capabilities", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> capabilities(HttpServletRequest req) {
        return run(req, "capabilities", Map.of(), AskScope.AGGREGATE);
    }

    @PostMapping(value = "/pivot", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> pivot(@RequestBody(required = false) Map<String, Object> body,
                                   HttpServletRequest req) {
        return run(req, "pivot", body == null ? Map.of() : body, AskScope.AGGREGATE);
    }

    @GetMapping(value = "/period/{period}/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> summary(@PathVariable String period, HttpServletRequest req) {
        return run(req, "period_summary", Map.of("period", period), AskScope.AGGREGATE);
    }

    @GetMapping(value = "/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> latestSummary(HttpServletRequest req) {
        return run(req, "period_summary", Map.of(), AskScope.AGGREGATE);
    }

    /**
     * agent 报告「我够不着」。
     *
     * <p>这不是错误上报,是<b>产品输入</b>:agent 的够不着变成下一版加接口的依据,
     * 比我们坐着猜用户要什么准得多。</p>
     */
    @PostMapping(value = "/unmet", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> unmet(@RequestBody(required = false) Map<String, Object> body,
                                   HttpServletRequest req) {
        var pass = guard.check(req, AskScope.AGGREGATE, "unmet");
        if (!pass.ok()) return denied(pass.result());
        String q = str(body, "question", 512);
        String need = str(body, "needed", 512);
        if (q == null || q.isBlank()) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", "question 必填"));
        }
        unmetMapper.insert(pass.familyId(), q, need);
        return ResponseEntity.ok(Map.of("ok", true,
                "note", "已记下。请如实告诉用户这一项你看不到,不要用别的数字代替。"));
    }

    // ──────────────────────── 内部 ────────────────────────

    private ResponseEntity<?> run(HttpServletRequest req, String tool,
                                  Map<String, Object> args, AskScope required) {
        var pass = guard.check(req, required, tool);
        if (!pass.ok()) return denied(pass.result());

        AskToolResult r = dispatcher.call(pass.familyId(), tool, args, pass.scope());
        if (!r.ok()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", r.error());
            if (r.meta().containsKey("allowed")) err.put("allowed", r.meta().get("allowed"));
            // 422:参数不对时把「可用取值」回给调用方,让它自己改,而不是一句「失败」把对话卡死
            return ResponseEntity.unprocessableEntity().body(err);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", r.data());
        out.put("meta", r.meta());
        if (!r.citations().isEmpty()) out.put("citations", r.citations());
        return ResponseEntity.ok(out);
    }

    /** 未通过:除 scope(403)与限流(429)外一律 404 空体 —— 不透露任何信息 */
    private ResponseEntity<?> denied(AskAuditResult r) {
        if (r == AskAuditResult.SCOPE) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "scope_required"));
        }
        if (r == AskAuditResult.RATE) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "30")
                    .body(Map.of("error", "rate_limited", "retryAfter", 30));
        }
        return ResponseEntity.notFound().build();
    }

    private static String str(Map<String, Object> m, String k, int max) {
        if (m == null || m.get(k) == null) return null;
        String s = String.valueOf(m.get(k)).trim();
        return s.length() <= max ? s : s.substring(0, max);
    }
}
