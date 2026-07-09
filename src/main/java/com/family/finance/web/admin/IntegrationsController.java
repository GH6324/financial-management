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
    private final java.util.List<com.family.finance.service.broker.BrokerClient> brokerClients; // v0.15 券商测试连接

    @GetMapping
    public String page(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        long fid = me.getFamilyId();
        // LLM
        model.addAttribute("qwenKeyConfigured",     configService.isPrivateKeyConfigured(fid, FamilyConfigService.K_LLM_QWEN_KEY));
        model.addAttribute("deepseekKeyConfigured", configService.isPrivateKeyConfigured(fid, FamilyConfigService.K_LLM_DEEPSEEK_KEY));
        model.addAttribute("llmMaxTokens",          configService.getInt(fid,  FamilyConfigService.K_LLM_MAX_TOKENS, 2000));
        model.addAttribute("llmTimeoutSeconds",     configService.getInt(fid,  FamilyConfigService.K_LLM_TIMEOUT_SECS, 25));
        // v0.14 · LLM 供应商自选 / 温度 / 模型级联
        model.addAttribute("llmPrimaryVendor",      configService.getString(fid, FamilyConfigService.K_LLM_PRIMARY_VENDOR, "qwen"));
        model.addAttribute("llmTemperature",        configService.getString(fid, FamilyConfigService.K_LLM_TEMPERATURE, "0.5"));
        model.addAttribute("llmModel",              configService.getString(fid, FamilyConfigService.K_LLM_MODEL, "auto"));
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

    /** ① LLM · keys(留空保原值)+ max_tokens + timeout */
    @PostMapping("/llm")
    public String saveLlm(@AuthenticationPrincipal MemberPrincipal me,
                          @RequestParam(value = "qwenKey", required = false) String qwenKey,
                          @RequestParam(value = "deepseekKey", required = false) String deepseekKey,
                          @RequestParam(value = "primaryVendor", required = false) String primaryVendor,
                          @RequestParam(value = "temperature", required = false) Double temperature,
                          @RequestParam(value = "model", required = false) String model,
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
        // v0.14 · 主选供应商(仅 qwen/deepseek,否则回落 qwen)
        String vendor = "deepseek".equalsIgnoreCase(primaryVendor) ? "deepseek" : "qwen";
        configService.set(fid, FamilyConfigService.K_LLM_PRIMARY_VENDOR, vendor);
        // v0.14 · 温度 0~1(默认 0.5)
        double temp = temperature == null ? 0.5 : Math.max(0.0, Math.min(1.0, temperature));
        configService.set(fid, FamilyConfigService.K_LLM_TEMPERATURE, String.valueOf(temp));
        // v0.14 · 模型(级联下拉;越权/空 → auto)· 校验属于所选供应商内置清单,否则回落 auto
        String mdl = normalizeLlmModel(vendor, model);
        configService.set(fid, FamilyConfigService.K_LLM_MODEL, mdl);
        int mt = Math.max(500, Math.min(maxTokens, 8000));
        int ts = Math.max(5, Math.min(timeoutSeconds, 120));
        configService.set(fid, FamilyConfigService.K_LLM_MAX_TOKENS, String.valueOf(mt));
        configService.set(fid, FamilyConfigService.K_LLM_TIMEOUT_SECS, String.valueOf(ts));
        // 审计 · 不记 key 明文(§22.6 私密红线)
        auditLogService.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                "family_runtime_config", fid,
                "LLM 配置 · qwenKey=" + (configService.isPrivateKeyConfigured(fid, FamilyConfigService.K_LLM_QWEN_KEY) ? "已配" : "未配")
                + " · deepseekKey=" + (configService.isPrivateKeyConfigured(fid, FamilyConfigService.K_LLM_DEEPSEEK_KEY) ? "已配" : "未配")
                + " · primaryVendor=" + vendor + " · temperature=" + temp + " · model=" + mdl
                + " · maxTokens=" + mt + " · timeout=" + ts + "s");
        ra.addFlashAttribute("flash", "LLM 配置已保存 · 主选=" + vendor + " · 下次调用生效");
        return "redirect:/admin/integrations";
    }

    /** v0.14 · 模型级联校验:必须属于所选供应商内置清单,否则回落 auto(防越权/打错型号) */
    private static String normalizeLlmModel(String vendor, String model) {
        if (model == null || model.isBlank() || model.equalsIgnoreCase("auto")) return "auto";
        String m = model.trim();
        java.util.List<String> allowed = "deepseek".equals(vendor)
                ? java.util.List.of("deepseek-chat", "deepseek-reasoner")
                : java.util.List.of("qwen-plus", "qwen-flash", "qwen-max");
        return allowed.stream().anyMatch(a -> a.equalsIgnoreCase(m)) ? m : "auto";
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
            String detail = client.testConnection(fid);
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
    static String brokerError(String rawMsg) {
        String m = rawMsg == null ? "" : rawMsg.toLowerCase(java.util.Locale.ROOT);
        if (m.contains("待真机接线") || m.contains("unsupported")) return "适配器待真机接线(需在你的环境接通 OpenD / 凭据)";
        if (m.contains("未配置") || m.contains("not configured") || m.contains("未配")) return "凭据未配置(请先填好并保存)";
        if (m.contains("timeout") || m.contains("超时") || m.contains("connect") || m.contains("i/o") || m.contains("unknownhost"))
            return "网络不通或超时(OpenD 未启动?)";
        if (m.contains("sign") || m.contains("invalid") || m.contains("401") || m.contains("403") || m.contains("unauthor") || m.contains("permission"))
            return "凭据无效或无权限";
        return "调用失败(已脱敏)";
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
