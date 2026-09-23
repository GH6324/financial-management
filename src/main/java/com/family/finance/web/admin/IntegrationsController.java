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
    private final java.util.List<com.family.finance.service.broker.BrokerClient> brokerClients; // v0.15 券商测试连接

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
        // v0.15 · 券商只读同步(私钥不回显)
        model.addAttribute("tigerId",               configService.getString(fid, FamilyConfigService.K_BROKER_TIGER_ID, ""));
        model.addAttribute("tigerKeyConfigured",    configService.isPrivateKeyConfigured(fid, FamilyConfigService.K_BROKER_TIGER_KEY));
        model.addAttribute("tigerAccount",          configService.getString(fid, FamilyConfigService.K_BROKER_TIGER_ACCOUNT, ""));
        model.addAttribute("futuHost",              configService.getString(fid, FamilyConfigService.K_BROKER_FUTU_HOST, ""));
        model.addAttribute("futuPort",              configService.getString(fid, FamilyConfigService.K_BROKER_FUTU_PORT, "11111"));
        model.addAttribute("brokerSyncCron",        configService.getString(fid, FamilyConfigService.K_BROKER_SYNC_CRON, "0 45 16 * * MON-FRI"));
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

    /**
     * ⑥ 券商只读同步 · 老虎(tiger_id + RSA 私钥 + 账户)+ 富途(OpenD host/port)+ 同步 cron。
     *
     * <p>私密红线:RSA 私钥留空 = 保原值、永不回显、audit 只记"已配/未配"不记明文;
     * 只读铁律:此处不存交易密码、不申请任何写权限。</p>
     */
    @PostMapping("/broker")
    public String saveBroker(@AuthenticationPrincipal MemberPrincipal me,
                             @RequestParam(value = "tigerId", required = false) String tigerId,
                             @RequestParam(value = "tigerKey", required = false) String tigerKey,
                             @RequestParam(value = "tigerAccount", required = false) String tigerAccount,
                             @RequestParam(value = "futuHost", required = false) String futuHost,
                             @RequestParam(value = "futuPort", required = false) String futuPort,
                             @RequestParam("brokerSyncCron") String brokerSyncCron,
                             RedirectAttributes ra) {
        long fid = me.getFamilyId();
        configService.set(fid, FamilyConfigService.K_BROKER_TIGER_ID, tigerId == null ? "" : tigerId.trim());
        // 私钥:留空保原值(与 LLM key 同策略)
        if (tigerKey != null && !tigerKey.isBlank()) {
            configService.set(fid, FamilyConfigService.K_BROKER_TIGER_KEY, tigerKey.trim());
        }
        configService.set(fid, FamilyConfigService.K_BROKER_TIGER_ACCOUNT, tigerAccount == null ? "" : tigerAccount.trim());
        configService.set(fid, FamilyConfigService.K_BROKER_FUTU_HOST, futuHost == null ? "" : futuHost.trim());
        configService.set(fid, FamilyConfigService.K_BROKER_FUTU_PORT, sanitize(futuPort, "11111"));
        configService.set(fid, FamilyConfigService.K_BROKER_SYNC_CRON, sanitize(brokerSyncCron, "0 45 16 * * MON-FRI"));
        schedulerConfig.rescheduleAll();
        // 审计 · 不记私钥明文
        auditLogService.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                "family_runtime_config", fid,
                "券商同步配置 · tigerId=" + (tigerId != null && !tigerId.isBlank() ? "已填" : "空")
                + " · tigerKey=" + (configService.isPrivateKeyConfigured(fid, FamilyConfigService.K_BROKER_TIGER_KEY) ? "已配" : "未配")
                + " · futuOpenD=" + (futuHost != null && !futuHost.isBlank() ? "已填" : "空")
                + " · cron=" + brokerSyncCron);
        ra.addFlashAttribute("flash", "券商同步配置已保存 · cron 已重排 · 只读、永不下单");
        return "redirect:/admin/integrations";
    }

    /**
     * ⑥ 券商 · 一键测试连接 · 用<b>已保存</b>凭据只拉一次账户/资产验证只读链路通不通。
     * <p>只读铁律:测试也只走查询接口,绝不下单;失败原因脱敏后展示。</p>
     */
    @PostMapping("/broker/test")
    public String testBroker(@AuthenticationPrincipal MemberPrincipal me,
                             @RequestParam("vendor") String vendor,
                             RedirectAttributes ra) {
        long fid = me.getFamilyId();
        com.family.finance.domain.broker.BrokerVendor v;
        try {
            v = com.family.finance.domain.broker.BrokerVendor.valueOf(vendor.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            ra.addFlashAttribute("flashError", "未知券商:" + vendor);
            return "redirect:/admin/integrations";
        }
        com.family.finance.service.broker.BrokerClient client = brokerClients.stream()
                .filter(c -> c.vendor() == v).findFirst().orElse(null);
        if (client == null) {
            ra.addFlashAttribute("flashError", v.getLabel() + " 客户端不可用");
            return "redirect:/admin/integrations";
        }
        try {
            String detail = client.testConnection(fid, null).summary();   // 全局默认凭据(link=null)
            auditLogService.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                    "family_runtime_config", fid, "券商测试连接 · " + v.getLabel() + " · 成功");
            ra.addFlashAttribute("flash", v.getLabel() + " 测试连接成功 · " + detail);
        } catch (Exception e) {
            String reason = brokerError(e.getMessage());
            auditLogService.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                    "family_runtime_config", fid, "券商测试连接 · " + v.getLabel() + " · 失败:" + reason);
            ra.addFlashAttribute("flashError", v.getLabel() + " 测试失败 · " + reason);
        }
        return "redirect:/admin/integrations";
    }

    /** 券商测试异常 message 归类成无敏感信息的友好原因(绝不含私钥 / 原始 body)。 */
    public static String brokerError(String rawMsg) {
        String m = rawMsg == null ? "" : rawMsg.toLowerCase(java.util.Locale.ROOT);
        if (m.contains("待真机接线") || m.contains("unsupported")) return "适配器待真机接线(需在你的环境接通 OpenD / 凭据)";
        if (m.contains("未配置") || m.contains("not configured") || m.contains("未配")) return "凭据未配置(请先填好并保存)";
        if (m.contains("timeout") || m.contains("超时") || m.contains("connect") || m.contains("i/o") || m.contains("unknownhost"))
            return "网络不通或超时(OpenD 未启动?)";
        if (m.contains("sign") || m.contains("invalid") || m.contains("401") || m.contains("403") || m.contains("unauthor") || m.contains("permission"))
            return "凭据无效或无权限";
        return "调用失败(已脱敏)";
    }

    private static String sanitize(String cron, String fallback) {
        return (cron == null || cron.isBlank()) ? fallback : cron.trim();
    }
}
