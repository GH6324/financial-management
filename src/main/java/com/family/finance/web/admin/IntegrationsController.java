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
 * <p>v1.24.5 起大模型挪去「AI 接入」,v1.26 起券商同步挪去 {@link BrokerSettingsController}(/admin/broker)——
 * 这一页只管系统自己拉的公共数据:行情 / 汇率 / 贵金属 / 宏观基准。
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

    @GetMapping
    public String page(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        long fid = me.getFamilyId();
        // v0.14 · 贵金属价格源 / cron
        model.addAttribute("metalPriceSource",      configService.getString(fid, FamilyConfigService.K_METAL_PRICE_SOURCE, "sge"));
        model.addAttribute("metalCron",             configService.getString(fid, FamilyConfigService.K_METAL_CRON, "0 20 16 * * MON-FRI"));
        // 股票
        model.addAttribute("stockEnabled",          configService.getBoolean(fid, FamilyConfigService.K_STOCK_ENABLED, false));
        model.addAttribute("stockCronUs",           configService.getString(fid,  FamilyConfigService.K_STOCK_CRON_US, "0 5 6 * * *"));
        model.addAttribute("stockCronCn",           configService.getString(fid,  FamilyConfigService.K_STOCK_CRON_CN, "0 10 16 * * MON-FRI"));
        model.addAttribute("stockCronHk",           configService.getString(fid,  FamilyConfigService.K_STOCK_CRON_HK, "0 30 16 * * MON-FRI"));
        model.addAttribute("stockCronCrypto",       configService.getString(fid,  FamilyConfigService.K_STOCK_CRON_CRYPTO, "0 15 6 * * *"));
        // FX
        model.addAttribute("fxCron",                configService.getString(fid,  FamilyConfigService.K_FX_CRON, "0 30 2 1 * ?"));
        // 券商同步(富途 / 老虎 / 盈透)v1.26 起自己一页:BrokerSettingsController · /admin/broker
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

    /** ⑤ 贵金属价格源(仅新建持仓默认)+ 拉价 cron · v0.14 */
    @PostMapping("/precious-metal")
    public String savePreciousMetal(@AuthenticationPrincipal MemberPrincipal me,
                                    @RequestParam("source") String source,
                                    @RequestParam("cronMetal") String cronMetal,
                                    RedirectAttributes ra) {
        long fid = me.getFamilyId();
        String src = "intl".equalsIgnoreCase(source) ? "intl" : "sge";
        configService.set(fid, FamilyConfigService.K_METAL_PRICE_SOURCE, src);
        configService.set(fid, FamilyConfigService.K_METAL_CRON, sanitize(cronMetal, "0 20 16 * * MON-FRI"));
        schedulerConfig.rescheduleAll();
        auditLogService.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                "family_runtime_config", fid, "贵金属 · 默认源=" + src + " · cron=" + cronMetal);
        ra.addFlashAttribute("flash", "贵金属配置已保存 · 默认源=" + (src.equals("sge") ? "上海 SGE" : "国际现货") + " · cron 已重排");
        return "redirect:/admin/integrations";
    }

    /** ② 股票自动拉取 · 开关 + 4 市场 cron(US/CN/HK/加密)· 贵金属 cron 见 /precious-metal */
    @PostMapping("/stock")
    public String saveStock(@AuthenticationPrincipal MemberPrincipal me,
                            @RequestParam(value = "enabled", defaultValue = "false") boolean enabled,
                            @RequestParam("cronUs") String cronUs,
                            @RequestParam("cronCn") String cronCn,
                            @RequestParam("cronHk") String cronHk,
                            @RequestParam("cronCrypto") String cronCrypto,
                            RedirectAttributes ra) {
        long fid = me.getFamilyId();
        configService.set(fid, FamilyConfigService.K_STOCK_ENABLED, String.valueOf(enabled));
        configService.set(fid, FamilyConfigService.K_STOCK_CRON_US, sanitize(cronUs, "0 5 6 * * *"));
        configService.set(fid, FamilyConfigService.K_STOCK_CRON_CN, sanitize(cronCn, "0 10 16 * * MON-FRI"));
        configService.set(fid, FamilyConfigService.K_STOCK_CRON_HK, sanitize(cronHk, "0 30 16 * * MON-FRI"));
        configService.set(fid, FamilyConfigService.K_STOCK_CRON_CRYPTO, sanitize(cronCrypto, "0 15 6 * * *"));
        // 重排 cron(立即生效)
        schedulerConfig.rescheduleAll();
        auditLogService.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                "family_runtime_config", fid,
                "股票拉取 · enabled=" + enabled
                + " · cron[US]=" + cronUs + " · cron[CN]=" + cronCn
                + " · cron[HK]=" + cronHk + " · cron[CRYPTO]=" + cronCrypto);
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
