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
        return "admin/integrations";
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

    private static String sanitize(String cron, String fallback) {
        return (cron == null || cron.isBlank()) ? fallback : cron.trim();
    }
}
