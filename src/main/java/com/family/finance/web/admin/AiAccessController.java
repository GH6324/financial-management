package com.family.finance.web.admin;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.ask.AskAccessToken;
import com.family.finance.domain.ask.AskScope;
import com.family.finance.repository.AskAuditMapper;
import com.family.finance.repository.AskUnmetNeedMapper;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.NavService;
import com.family.finance.service.ask.AccessTokenService;
import com.family.finance.service.ask.AskConversationService;
import com.family.finance.service.ask.AskPromptBuilder;
import com.family.finance.service.ask.AskToolRegistry;
import com.family.finance.service.ask.runtime.ManagedAgentRuntime;
import com.family.finance.service.config.FamilyConfigService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.family.finance.service.config.FamilyConfigService.*;

/**
 * v1.19 · 管理页「AI 接入」。
 *
 * <p>这一页做四件事:发凭据、换绑、断开、看审计。设计上有两条刻意的取舍:</p>
 *
 * <ol>
 *   <li><b>明文只在一次响应里出现</b>,之后任何地方都取不回 —— 包括这一页自己。
 *       所以「复制」按钮必须在生成那一屏就给全,不能指望用户回头再来拿。</li>
 *   <li><b>续期</b>与<b>换密钥</b>是两个按钮。多数人点「重新生成」其实只是看到「即将过期」,
 *       而换密钥意味着他得再去百炼跑一趟(百炼改配置要「停止部署 → 改 → 重新部署」)。
 *       拆开之后,那类需求变成零成本的一键续期。</li>
 * </ol>
 */
@Controller
@RequiredArgsConstructor
public class AiAccessController {

    private static final int AUDIT_LIMIT = 50;

    private final AccessTokenService tokenService;
    private final AskAuditMapper auditMapper;
    private final AskUnmetNeedMapper unmetMapper;
    private final AskToolRegistry registry;
    private final NavService navService;
    private final AuditLogService auditLogService;
    private final FamilyConfigService configService;
    private final AskConversationService askConversations;
    private final AskPromptBuilder promptBuilder;
    private final ManagedAgentRuntime managedAgentRuntime;

    @GetMapping("/admin/ai-access")
    public String page(@AuthenticationPrincipal MemberPrincipal me, HttpServletRequest req, Model model) {
        long fam = me.getFamilyId();
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));

        List<AskAccessToken> tokens = tokenService.list(fam);
        // 按接入点分组:换绑期间同一个点下有两把(旧的 + 新的)
        Map<Long, List<AskAccessToken>> byPoint = new LinkedHashMap<>();
        for (AskAccessToken t : tokens) {
            byPoint.computeIfAbsent(t.getAccessPointId(), k -> new ArrayList<>()).add(t);
        }
        List<Map<String, Object>> points = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (var e : byPoint.entrySet()) {
            List<AskAccessToken> ks = e.getValue();
            ks.sort(Comparator.comparing(AskAccessToken::getCreatedAt));
            AskAccessToken primary = ks.get(ks.size() - 1);
            AskAccessToken old = ks.size() > 1 ? ks.get(0) : null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pointId", e.getKey());
            m.put("name", primary.getName());
            m.put("scope", primary.scopeEnum().getLabel());
            m.put("prefix", primary.getTokenPrefix());
            m.put("tokenId", primary.getId());
            m.put("expiresAt", primary.getExpiresAt());
            m.put("daysLeft", primary.daysToExpiry(now));
            m.put("expiringSoon", primary.daysToExpiry(now) <= AccessTokenService.WARN_DAYS);
            m.put("lastUsedAt", primary.getLastUsedAt());
            m.put("neverUsed", primary.getFirstUsedAt() == null);
            m.put("rotating", old != null);
            m.put("oldPrefix", old == null ? null : old.getTokenPrefix());
            points.add(m);
        }

        model.addAttribute("points", points);
        model.addAttribute("enabled", !points.isEmpty());
        model.addAttribute("audits", auditMapper.recent(fam, AUDIT_LIMIT));
        model.addAttribute("unmet", unmetMapper.recent(fam, 10));
        model.addAttribute("toolCount", registry.all().size());
        model.addAttribute("toolNames", registry.all().stream()
                .map(com.family.finance.service.ask.AskTool::name).toList());
        model.addAttribute("baseUrl", guessBaseUrl(req));

        // ── 超级 Agent ──
        model.addAttribute("askEnabled", configService.getBoolean(fam, K_ASK_ENABLED, false));
        model.addAttribute("askRuntime", askConversations.runtime().code());
        model.addAttribute("askBlocked", askConversations.blockedReason(fam));
        model.addAttribute("askPublicBaseUrl", configService.getString(fam, K_ASK_PUBLIC_BASE_URL, ""));
        model.addAttribute("askWorkspaceId", configService.getString(fam, K_ASK_MA_WORKSPACE, ""));
        model.addAttribute("askMcpServerId", configService.getString(fam, K_ASK_MA_MCP_SERVER, ""));
        model.addAttribute("askAgentId", configService.getString(fam, K_ASK_MA_AGENT_ID, ""));
        // 教程里那段示例配置 —— **由 Java 生成**,不在模板里手拼。
        // 手拼那版有个不显眼的 bug:Thymeleaf 字符串字面量里的 \n 不是换行,
        // 渲染出来是带字面 \n 的一行,用户照抄进百炼就是一段无效 JSON。
        model.addAttribute("mcpConfigSample",
                managedAgentRuntime.mcpConfigJson(guessBaseUrl(req), "你的口令"));
        // v1.19.7 · 自测用的 curl —— 百炼排障文档建议的第一步就是「用 curl 直连下游服务」,
        // 它能立刻分清「百炼那边没配对」和「你的服务根本不通」。同样由 Java 拼,
        // 理由和上面那段一样:模板里的 \n 不是换行。
        model.addAttribute("mcpCurlSample", String.join("\n",
                "curl -sS " + guessBaseUrl(req) + "/mcp \\",
                "  -H 'Content-Type: application/json' \\",
                "  -H 'Authorization: Bearer 你的口令' \\",
                "  -d '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}'"));
        // 每条 runtime 连同「现在能不能用、不能用是因为什么」一起给页面 ——
        // 只给一个禁用的单选框而不说原因,用户会以为是坏了
        model.addAttribute("runtimes", askConversations.allRuntimes().stream()
                .map(r -> Map.of("code", (Object) r.code(), "label", r.label(),
                        "reason", r.unavailableReason(fam) == null ? "" : r.unavailableReason(fam)))
                .map(m -> {
                    Map<String, Object> x = new LinkedHashMap<>(m);
                    if ("".equals(x.get("reason"))) x.put("reason", null);
                    return x;
                }).toList());
        return "admin/ai-access";
    }

    @PostMapping("/admin/ai-access/ask-settings")
    public String askSettings(@AuthenticationPrincipal MemberPrincipal me,
                              @RequestParam(defaultValue = "false") boolean enabled,
                              @RequestParam(required = false) String runtime,
                              @RequestParam(required = false) String publicBaseUrl,
                              @RequestParam(required = false) String workspaceId,
                              @RequestParam(required = false) String mcpServerId,
                              RedirectAttributes ra) {
        long fam = me.getFamilyId();
        configService.set(fam, K_ASK_ENABLED, String.valueOf(enabled));
        if (runtime != null && !runtime.isBlank()) configService.set(fam, K_ASK_RUNTIME, runtime.trim());
        configService.set(fam, K_ASK_PUBLIC_BASE_URL, trimSlash(publicBaseUrl));
        configService.set(fam, K_ASK_MA_WORKSPACE, nz(workspaceId));
        configService.set(fam, K_ASK_MA_MCP_SERVER, nz(mcpServerId));

        String blocked = askConversations.blockedReason(fam);
        // 保存成功不代表能用 —— 把「还差什么」当场说清,别让用户点进去才发现
        ra.addFlashAttribute(blocked == null ? "askNote" : "askError",
                blocked == null ? "已保存。现在任何一页右下角都能点开「超级 Agent」了。"
                                : "已保存,但还用不了:" + blocked);
        return "redirect:/admin/ai-access";
    }

    @PostMapping("/admin/ai-access/create-agent")
    public String createAgent(@AuthenticationPrincipal MemberPrincipal me, RedirectAttributes ra) {
        long fam = me.getFamilyId();
        try {
            String prompt = promptBuilder.build(fam, null, null);
            boolean update = !configService.getString(fam, K_ASK_MA_AGENT_ID, "").isBlank();
            if (update) {
                managedAgentRuntime.updateAgent(prompt, null);
                ra.addFlashAttribute("askNote", "已更新百炼上的 Agent 模板。已经开着的会话不受影响 ——"
                        + "百炼在创建会话时就锁定了模板版本,新模板对新会话生效。");
            } else {
                String id = managedAgentRuntime.createAgent(prompt, null);
                ra.addFlashAttribute("askNote", "已在百炼上创建 Agent(" + id + ")。");
            }
        } catch (Exception e) {
            ra.addFlashAttribute("askError", "创建失败:" + e.getMessage()
                    + " —— 先确认业务空间 ID、MCP 服务 ID 都对,而且百炼那边连得上你填的公网地址。");
        }
        return "redirect:/admin/ai-access";
    }

    private static String nz(String s) { return s == null ? "" : s.trim(); }

    /** 公网地址存的时候去掉尾斜杠 —— 拼 /mcp 时会多出一条 // 的路径,百炼那边可能就 404 了 */
    private static String trimSlash(String s) {
        String v = nz(s);
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    @PostMapping("/admin/ai-access/create")
    public String create(@AuthenticationPrincipal MemberPrincipal me,
                         @RequestParam(required = false) String name,
                         @RequestParam(required = false) String scope,
                         @RequestParam(required = false) Integer days,
                         HttpServletRequest req,
                         RedirectAttributes ra) {
        var issued = tokenService.create(me.getFamilyId(), name, AskScope.parse(scope),
                days == null ? AccessTokenService.DEFAULT_DAYS : days);
        auditLogService.record(me.getFamilyId(), me.getMemberId(),
                com.family.finance.domain.audit.AuditLogType.SYSTEM, "ask_access",
                issued.token().getId(),
                "新增 AI 接入点「" + issued.token().getName() + "」(" + issued.token().getTokenPrefix() + ")");
        // 明文只经这一次 flash 传给页面,不入库、不进日志
        ra.addFlashAttribute("freshToken", issued.plaintext());
        ra.addFlashAttribute("freshName", issued.token().getName());
        ra.addFlashAttribute("freshIsRotation", false);
        // 明文只经这一次 flash;配置 JSON 同源生成,保证用户复制走的是**能用的**那一份
        ra.addFlashAttribute("freshMcpConfig",
                managedAgentRuntime.mcpConfigJson(guessBaseUrl(req), issued.plaintext()));
        return "redirect:/admin/ai-access";
    }

    @PostMapping("/admin/ai-access/rotate")
    public String rotate(@AuthenticationPrincipal MemberPrincipal me,
                         @RequestParam long pointId,
                         RedirectAttributes ra) {
        try {
            var issued = tokenService.rotate(me.getFamilyId(), pointId);
            auditLogService.record(me.getFamilyId(), me.getMemberId(),
                    com.family.finance.domain.audit.AuditLogType.SYSTEM, "ask_access",
                    issued.token().getId(),
                    "更换 AI 接入口令「" + issued.token().getName() + "」· 旧口令在新口令首次被使用后自动失效");
            ra.addFlashAttribute("freshToken", issued.plaintext());
            ra.addFlashAttribute("freshName", issued.token().getName());
            ra.addFlashAttribute("freshIsRotation", true);
        } catch (IllegalStateException e) {
            ra.addFlashAttribute("askError", e.getMessage());
        }
        return "redirect:/admin/ai-access";
    }

    @PostMapping("/admin/ai-access/renew")
    public String renew(@AuthenticationPrincipal MemberPrincipal me,
                        @RequestParam long tokenId,
                        @RequestParam(required = false) Integer days,
                        RedirectAttributes ra) {
        tokenService.renew(tokenId, days == null ? AccessTokenService.DEFAULT_DAYS : days);
        ra.addFlashAttribute("askNote", "已续期。口令没有变 —— 百炼那边不用动。");
        return "redirect:/admin/ai-access";
    }

    @PostMapping("/admin/ai-access/kill")
    public String kill(@AuthenticationPrincipal MemberPrincipal me,
                       @RequestParam long pointId,
                       RedirectAttributes ra) {
        int n = tokenService.killAccessPoint(pointId);
        auditLogService.record(me.getFamilyId(), me.getMemberId(),
                com.family.finance.domain.audit.AuditLogType.SYSTEM, "ask_access", pointId,
                "紧急断开 AI 接入点 · " + n + " 把口令立刻失效");
        ra.addFlashAttribute("askNote", "已断开,共 " + n + " 把口令立刻失效。百炼那边的调用会直接失败,"
                + "你不用去改它 —— 想重新接入时新建一个接入点即可。");
        return "redirect:/admin/ai-access";
    }

    @PostMapping("/admin/ai-access/kill-all")
    public String killAll(@AuthenticationPrincipal MemberPrincipal me, RedirectAttributes ra) {
        int n = tokenService.killAll(me.getFamilyId());
        auditLogService.record(me.getFamilyId(), me.getMemberId(),
                com.family.finance.domain.audit.AuditLogType.SYSTEM, "ask_access", 0L,
                "关闭全部 AI 接入 · " + n + " 把口令立刻失效");
        ra.addFlashAttribute("askNote", "已全部关闭。接口现在对任何请求都返回「不存在」。");
        return "redirect:/admin/ai-access";
    }

    /** 猜一个对外可用的 base url 填进配置示例 —— 猜错也没关系,页面允许用户自己改 */
    private static String guessBaseUrl(HttpServletRequest req) {
        String proto = req.getHeader("X-Forwarded-Proto");
        String host = req.getHeader("X-Forwarded-Host");
        if (host == null || host.isBlank()) host = req.getHeader("Host");
        if (host == null || host.isBlank()) host = req.getServerName();
        return (proto == null || proto.isBlank() ? "https" : proto) + "://" + host;
    }
}
