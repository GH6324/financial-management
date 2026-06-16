package com.family.finance.web.admin;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.config.FamilyConfigService;
import com.family.finance.service.scheduling.DynamicScheduleConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * v0.4.18 · /admin/integrations · 第三方集成中心(详 prd/v0.4.md §22)。
 *
 * <p>3 段独立 form,各自 POST · 改完即生效(动态 cron 通过
 * {@link DynamicScheduleConfig#rescheduleAll()} 重排)。
 *
 * <p>私密红线(§22.6):LLM API key 留空保原值 · secret 永不回显 ·
 * audit log 仅记"已配/未配"不记明文 · `getString` 内含 env fallback。
 */
@Controller
@RequestMapping("/admin/integrations")
@RequiredArgsConstructor
public class IntegrationsController {

    private final FamilyConfigService configService;
    private final DynamicScheduleConfig schedulerConfig;
    private final AuditLogService auditLogService;
    private final com.family.finance.service.macro.MacroBenchmarkService macroService; // v0.5 FR-76
    private final java.util.List<com.family.finance.service.checkup.llm.LlmClient> llmClients; // v0.7 FR-131 测试连接

    @GetMapping
    public String page(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        long fid = me.getFamilyId();
        // LLM
        model.addAttribute("qwenKeyConfigured",     configService.isPrivateKeyConfigured(fid, FamilyConfigService.K_LLM_QWEN_KEY));
        model.addAttribute("deepseekKeyConfigured", configService.isPrivateKeyConfigured(fid, FamilyConfigService.K_LLM_DEEPSEEK_KEY));
        model.addAttribute("llmMaxTokens",          configService.getInt(fid,  FamilyConfigService.K_LLM_MAX_TOKENS, 2000));
        model.addAttribute("llmTimeoutSeconds",     configService.getInt(fid,  FamilyConfigService.K_LLM_TIMEOUT_SECS, 25));
        // 股票
        model.addAttribute("stockEnabled",          configService.getBoolean(fid, FamilyConfigService.K_STOCK_ENABLED, false));
        model.addAttribute("stockCronUs",           configService.getString(fid,  FamilyConfigService.K_STOCK_CRON_US, "0 5 6 * * *"));
        model.addAttribute("stockCronCn",           configService.getString(fid,  FamilyConfigService.K_STOCK_CRON_CN, "0 10 16 * * MON-FRI"));
        model.addAttribute("stockCronHk",           configService.getString(fid,  FamilyConfigService.K_STOCK_CRON_HK, "0 30 16 * * MON-FRI"));
        // FX
        model.addAttribute("fxCron",                configService.getString(fid,  FamilyConfigService.K_FX_CRON, "0 30 2 1 * ?"));
        // v0.5 FR-76 · 宏观基准 CPI/M2
        model.addAttribute("macroAll",      macroService.all());
        model.addAttribute("macroLatest",   macroService.latest());
        model.addAttribute("cpiAverages",   macroService.cpiAverages());
        model.addAttribute("m2Averages",    macroService.m2Averages());
        return "admin/integrations";
    }

    /** ④ 宏观基准 · 手动校正某年 CPI/M2(年度 cron 无稳定公开 API · 手动录入为可靠路径)· FR-76 */
    @PostMapping("/macro")
    public String saveMacro(@AuthenticationPrincipal MemberPrincipal me,
                            @RequestParam("year") int year,
                            @RequestParam(value = "cpi", required = false) java.math.BigDecimal cpi,
                            @RequestParam(value = "m2", required = false) java.math.BigDecimal m2,
                            RedirectAttributes ra) {
        if (year < 1980 || year > 2100) {
            ra.addFlashAttribute("flash", "年份不合法");
            return "redirect:/admin/integrations";
        }
        macroService.upsert(com.family.finance.domain.macro.MacroBenchmark.builder()
                .year(year).cpiHeadline(cpi).m2Growth(m2).source("manual").build());
        auditLogService.record(me.getFamilyId(), me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                "macro_benchmark", (long) year, "宏观基准校正 · " + year + " · CPI=" + cpi + " M2=" + m2);
        ra.addFlashAttribute("flash", "宏观基准 " + year + " 已更新 · 财富水位实时生效");
        return "redirect:/admin/integrations";
    }

    /** ① LLM · keys(留空保原值)+ max_tokens + timeout */
    @PostMapping("/llm")
    public String saveLlm(@AuthenticationPrincipal MemberPrincipal me,
                          @RequestParam(value = "qwenKey", required = false) String qwenKey,
                          @RequestParam(value = "deepseekKey", required = false) String deepseekKey,
                          @RequestParam("maxTokens") int maxTokens,
                          @RequestParam("timeoutSeconds") int timeoutSeconds,
                          RedirectAttributes ra) {
        long fid = me.getFamilyId();
        if (qwenKey != null && !qwenKey.isBlank()) {
            configService.set(fid, FamilyConfigService.K_LLM_QWEN_KEY, qwenKey.trim());
        }
        if (deepseekKey != null && !deepseekKey.isBlank()) {
            configService.set(fid, FamilyConfigService.K_LLM_DEEPSEEK_KEY, deepseekKey.trim());
        }
        int mt = Math.max(500, Math.min(maxTokens, 8000));
        int ts = Math.max(5, Math.min(timeoutSeconds, 120));
        configService.set(fid, FamilyConfigService.K_LLM_MAX_TOKENS, String.valueOf(mt));
        configService.set(fid, FamilyConfigService.K_LLM_TIMEOUT_SECS, String.valueOf(ts));
        // 审计 · 不记 key 明文(§22.6 私密红线)
        auditLogService.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                "family_runtime_config", fid,
                "LLM 配置 · qwenKey=" + (configService.isPrivateKeyConfigured(fid, FamilyConfigService.K_LLM_QWEN_KEY) ? "已配" : "未配")
                + " · deepseekKey=" + (configService.isPrivateKeyConfigured(fid, FamilyConfigService.K_LLM_DEEPSEEK_KEY) ? "已配" : "未配")
                + " · maxTokens=" + mt + " · timeout=" + ts + "s");
        ra.addFlashAttribute("flash", "LLM 配置已保存 · 下次调用生效");
        return "redirect:/admin/integrations";
    }

    /** ② 股票自动拉取 · 开关 + 3 市场 cron */
    @PostMapping("/stock")
    public String saveStock(@AuthenticationPrincipal MemberPrincipal me,
                            @RequestParam(value = "enabled", defaultValue = "false") boolean enabled,
                            @RequestParam("cronUs") String cronUs,
                            @RequestParam("cronCn") String cronCn,
                            @RequestParam("cronHk") String cronHk,
                            RedirectAttributes ra) {
        long fid = me.getFamilyId();
        configService.set(fid, FamilyConfigService.K_STOCK_ENABLED, String.valueOf(enabled));
        configService.set(fid, FamilyConfigService.K_STOCK_CRON_US, sanitize(cronUs, "0 5 6 * * *"));
        configService.set(fid, FamilyConfigService.K_STOCK_CRON_CN, sanitize(cronCn, "0 10 16 * * MON-FRI"));
        configService.set(fid, FamilyConfigService.K_STOCK_CRON_HK, sanitize(cronHk, "0 30 16 * * MON-FRI"));
        // 重排 cron(立即生效)
        schedulerConfig.rescheduleAll();
        auditLogService.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                "family_runtime_config", fid,
                "股票拉取 · enabled=" + enabled
                + " · cron[US]=" + cronUs + " · cron[CN]=" + cronCn + " · cron[HK]=" + cronHk);
        ra.addFlashAttribute("flash", "股票拉取配置已保存 · cron 已重排 · 不重启");
        return "redirect:/admin/integrations";
    }

    /** ③ FX 汇率拉取 cron */
    @PostMapping("/fx")
    public String saveFx(@AuthenticationPrincipal MemberPrincipal me,
                         @RequestParam("fxCron") String fxCron,
                         RedirectAttributes ra) {
        long fid = me.getFamilyId();
        configService.set(fid, FamilyConfigService.K_FX_CRON, sanitize(fxCron, "0 30 2 1 * ?"));
        schedulerConfig.rescheduleAll();
        auditLogService.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                "family_runtime_config", fid, "FX 拉取 cron = " + fxCron);
        ra.addFlashAttribute("flash", "FX 拉取配置已保存 · cron 已重排");
        return "redirect:/admin/integrations";
    }

    /**
     * v0.7 FR-131 · LLM 一键测试连接 · 用<b>已保存</b>的 key 发最小探测,验证链路通不通。
     *
     * <p>私密红线(决策 82):绝不回显 key、绝不把 key 进 flash / audit / 日志明文;
     * 失败原因经 {@link #classifyLlmError} 归类成无敏感信息的友好文案。
     */
    @PostMapping("/llm/test")
    public String testLlm(@AuthenticationPrincipal MemberPrincipal me,
                          @RequestParam("vendor") String vendor,
                          RedirectAttributes ra) {
        long fid = me.getFamilyId();
        String v = vendor == null ? "" : vendor.trim().toLowerCase(java.util.Locale.ROOT);
        String label = "qwen".equals(v) ? "Qwen" : "deepseek".equals(v) ? "DeepSeek" : v;
        String keyConst = "qwen".equals(v) ? FamilyConfigService.K_LLM_QWEN_KEY
                : "deepseek".equals(v) ? FamilyConfigService.K_LLM_DEEPSEEK_KEY : null;
        if (keyConst == null) {
            ra.addFlashAttribute("flashError", "未知厂商:" + v);
            return "redirect:/admin/integrations";
        }
        if (!configService.isPrivateKeyConfigured(fid, keyConst)) {
            ra.addFlashAttribute("flashError", label + " 未配置 Key · 请先填好并保存,再测试连接");
            return "redirect:/admin/integrations";
        }
        com.family.finance.service.checkup.llm.LlmClient client = llmClients.stream()
                .filter(c -> v.equals(c.vendor())).findFirst().orElse(null);
        if (client == null) {
            ra.addFlashAttribute("flashError", label + " 客户端不可用");
            return "redirect:/admin/integrations";
        }

        boolean ok;
        String reason;
        try {
            // 最小探测:极短 prompt,验证 key→端点→模型→解析 全链路通(与体检走同一路径)
            String out = client.chat("你是连通性自检,无视语义,只回复两个字:ok。", "ping");
            ok = out != null && !out.isBlank();
            reason = ok ? "可用" : "返回为空";
        } catch (Exception e) {
            ok = false;
            reason = classifyLlmError(e.getMessage());
        }

        // 审计 · 不记 key 明文(§22.6 / 决策 82)· 只记 vendor + 结果归类
        auditLogService.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                "family_runtime_config", fid,
                "LLM 测试连接 · " + label + " · " + (ok ? "成功" : "失败:" + reason));
        if (ok) {
            ra.addFlashAttribute("flash", label + " 测试连接成功 · " + reason);
        } else {
            ra.addFlashAttribute("flashError", label + " 测试失败 · " + reason);
        }
        return "redirect:/admin/integrations";
    }

    /** 把 LLM 调用异常 message 归类成<b>无敏感信息</b>的友好原因(绝不含 key / 不回显原始 body)。 */
    static String classifyLlmError(String rawMsg) {
        String m = rawMsg == null ? "" : rawMsg.toLowerCase(java.util.Locale.ROOT);
        if (m.contains("未配置") || m.contains("not configured")) return "Key 未配置";
        if (m.contains("arrearage") || m.contains("欠费") || m.contains("billoverdue") || m.contains("bill overdue"))
            return "账户欠费或账单过期";
        if (m.contains("quota") || m.contains("额度") || m.contains("freetier") || m.contains("insufficient"))
            return "免费额度已用尽(可换模型或等额度重置)";
        if (m.contains("401") || m.contains("403") || m.contains("invalid") || m.contains("incorrect")
                || m.contains("unauthor") || m.contains("forbidden") || m.contains("api key"))
            return "Key 无效或无权限";
        if (m.contains("timeout") || m.contains("超时") || m.contains("timed out")
                || m.contains("resourceaccess") || m.contains("connect") || m.contains("i/o") || m.contains("unknownhost"))
            return "网络不通或超时";
        java.util.regex.Matcher sm = java.util.regex.Pattern.compile("status=(\\d{3})").matcher(m);
        if (sm.find()) return "调用失败(已脱敏 · HTTP " + sm.group(1) + ")";
        return "调用失败(已脱敏)";
    }

    private static String sanitize(String cron, String fallback) {
        return (cron == null || cron.isBlank()) ? fallback : cron.trim();
    }
}
