package com.family.finance.web.admin;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.checkup.llm.LlmCatalog;
import com.family.finance.service.checkup.llm.LlmInvocation;
import com.family.finance.service.checkup.llm.LlmSettings;
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
    private final com.family.finance.service.checkup.llm.LlmRouter llmRouter;  // v0.7 FR-131 测试连接 · v1.13 收口到路由
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;   // v1.13 · 型号目录注入模板
    private final java.util.List<com.family.finance.service.broker.BrokerClient> brokerClients; // v0.15 券商测试连接

    @GetMapping
    public String page(@AuthenticationPrincipal MemberPrincipal me, Model model) {
        long fid = me.getFamilyId();
        // LLM · v1.13 三级(平台 / 系列 / 型号)· 主备 + 视觉各一组三元组
        addLlmAttributes(fid, model);
        model.addAttribute("llmMaxTokens",          configService.getInt(fid,  FamilyConfigService.K_LLM_MAX_TOKENS, 2000));
        model.addAttribute("llmTimeoutSeconds",     configService.getInt(fid,  FamilyConfigService.K_LLM_TIMEOUT_SECS, 25));
        model.addAttribute("llmTemperature",        configService.getString(fid, FamilyConfigService.K_LLM_TEMPERATURE, "0.5"));
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

    // ==================== ① LLM(v1.13 三级模型)====================

    /**
     * v1.13 · 管理页 LLM 段的展示数据。<b>当前生效值一律从 {@link LlmSettings} 读</b>,
     * 而不是直接读配置键 —— 老家庭升级后新键还是空的,直接读键页面会显示成「什么都没选」,
     * 但实际调用走的是派生出来的百炼/通义千问。让页面和实际调用说同一件事。
     */
    private void addLlmAttributes(long fid, Model model) {
        LlmSettings s = LlmSettings.load(configService, fid);
        model.addAttribute("llmLegacy", s.legacy());   // 还没保存过新配置 → 页面提示「保存一次即可固化」
        // 三把 key 各自的「已配/未配」(永不回显值)
        model.addAttribute("qwenKeyConfigured",     configService.isPrivateKeyConfigured(fid, FamilyConfigService.K_LLM_QWEN_KEY));
        model.addAttribute("deepseekKeyConfigured", configService.isPrivateKeyConfigured(fid, FamilyConfigService.K_LLM_DEEPSEEK_KEY));
        model.addAttribute("arkKeyConfigured",      configService.isPrivateKeyConfigured(fid, FamilyConfigService.K_LLM_ARK_KEY));
        // 主选三元组
        model.addAttribute("llmPlatform",       s.primary().platform());
        model.addAttribute("llmFamily",         s.primary().family());
        model.addAttribute("llmModelId",        s.primary().model() == null ? "" : s.primary().model());
        model.addAttribute("llmPrimaryDisplay", s.primary().display());
        // 备选三元组(可为空 = 不设备选)
        LlmInvocation backup = s.backup().orElse(null);
        model.addAttribute("llmBackupPlatform", backup == null ? "" : backup.platform());
        model.addAttribute("llmBackupFamily",   backup == null ? "" : backup.family());
        model.addAttribute("llmBackupModelId",  backup == null || backup.model() == null ? "" : backup.model());
        model.addAttribute("llmBackupDisplay",  backup == null ? "未设置" : backup.display());
        // 视觉三元组 + 独立开关(FR-362:开关不再编码在型号里)
        model.addAttribute("llmVisionEnabled",  s.visionEnabled());
        model.addAttribute("llmVisionPlatform", s.vision().platform());
        model.addAttribute("llmVisionFamily",   s.vision().family());
        model.addAttribute("llmVisionModelId",  s.vision().model() == null ? "" : s.vision().model());
        model.addAttribute("llmVisionDisplay",  s.vision().display());
        // 型号目录(级联下拉的唯一数据源 · FR-364)· 服务端注入,模板里不再有第二份写死清单
        model.addAttribute("llmCatalogJson", catalogJson());
        // 平台下拉的 option 仍由服务端渲染(而不是全交给 JS):JS 挂了的话至少还能提交一组合法平台,
        // 而且「哪些平台能做视觉」这件事按目录如实过滤 —— DeepSeek 官方没有视觉系列,就不该出现在截图识别里。
        model.addAttribute("llmPlatformsText",   platformsWith(LlmCatalog.Modality.TEXT));
        model.addAttribute("llmPlatformsVision", platformsWith(LlmCatalog.Modality.VISION));
    }

    /** 支持该形态的平台(视觉:DeepSeek 官方没有视觉系列 → 不出现在截图识别的平台下拉里) */
    private static java.util.List<LlmCatalog.Platform> platformsWith(LlmCatalog.Modality m) {
        return LlmCatalog.PLATFORMS.stream().filter(p -> !p.families(m).isEmpty()).toList();
    }

    /**
     * 把 {@link LlmCatalog} 序列化给前端级联用。<b>只挑前端需要的字段</b> ——
     * {@code keyName} / {@code baseUrl} 是服务端实现细节,没有理由出现在 HTML 里。
     */
    private String catalogJson() {
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (LlmCatalog.Platform p : LlmCatalog.PLATFORMS) {
            java.util.List<java.util.Map<String, Object>> fams = new java.util.ArrayList<>();
            for (LlmCatalog.Family f : p.families()) {
                java.util.List<java.util.Map<String, Object>> models = f.models().stream()
                        .map(m -> java.util.Map.<String, Object>of("id", m.id(), "label", m.label()))
                        .toList();
                java.util.Map<String, Object> fm = new java.util.LinkedHashMap<>();
                fm.put("code", f.code());
                fm.put("label", f.label());
                fm.put("modality", f.modality().name());
                fm.put("mustFillModel", f.requiresExplicitModel());
                fm.put("defaultModel", f.defaultModel() == null ? "" : f.defaultModel());
                fm.put("models", models);
                fams.add(fm);
            }
            java.util.Map<String, Object> pm = new java.util.LinkedHashMap<>();
            pm.put("code", p.code());
            pm.put("label", p.label());
            pm.put("keyHowTo", p.keyHowTo());
            pm.put("modelRotation", p.modelRotation());
            pm.put("families", fams);
            out.add(pm);
        }
        try {
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * ① LLM · 三把 key(留空保原值)+ 主备/视觉三元组 + 温度 / max_tokens / timeout。
     *
     * <p><b>校验先全跑完再落库</b>:任何一处不合法就整单退回(flashError + 原样重填),
     * 不写一个字。v0.14 那套「越权型号静默回落 auto」在三级模型下是有害的 ——
     * 方舟的 {@code ep-xxxx} 必然不在任何内置清单里,静默回落的表现是
     * 「页面显示着我填的型号、实际调的是别的」,用户查不出来。宁可当面拒绝。</p>
     */
    @PostMapping("/llm")
    public String saveLlm(@AuthenticationPrincipal MemberPrincipal me,
                          @RequestParam(value = "qwenKey", required = false) String qwenKey,
                          @RequestParam(value = "deepseekKey", required = false) String deepseekKey,
                          @RequestParam(value = "arkKey", required = false) String arkKey,
                          @RequestParam(value = "platform", required = false) String platform,
                          @RequestParam(value = "family", required = false) String family,
                          @RequestParam(value = "modelId", required = false) String modelId,
                          @RequestParam(value = "backupPlatform", required = false) String backupPlatform,
                          @RequestParam(value = "backupFamily", required = false) String backupFamily,
                          @RequestParam(value = "backupModelId", required = false) String backupModelId,
                          @RequestParam(value = "visionEnabled", defaultValue = "false") boolean visionEnabled,
                          @RequestParam(value = "visionPlatform", required = false) String visionPlatform,
                          @RequestParam(value = "visionFamily", required = false) String visionFamily,
                          @RequestParam(value = "visionModelId", required = false) String visionModelId,
                          @RequestParam(value = "temperature", required = false) Double temperature,
                          @RequestParam("maxTokens") int maxTokens,
                          @RequestParam("timeoutSeconds") int timeoutSeconds,
                          RedirectAttributes ra) {
        long fid = me.getFamilyId();

        // ── 先校验三组三元组(一处不合法就整单退回) ──
        LlmInvocation primary, backup, vision;
        try {
            primary = parseTriple("主选", platform, family, modelId, LlmCatalog.Modality.TEXT, true);
            backup  = isBlank(backupPlatform) ? null
                    : parseTriple("备选", backupPlatform, backupFamily, backupModelId, LlmCatalog.Modality.TEXT, true);
            // 视觉:关掉时不强求填型号(用户可能只是暂时不用截图导入,配置留着下次开)
            vision  = parseTriple("截图识别", visionPlatform, visionFamily, visionModelId,
                    LlmCatalog.Modality.VISION, visionEnabled);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
            return "redirect:/admin/integrations";
        }
        if (backup != null && backup.equals(primary)) {
            ra.addFlashAttribute("flashError", "备选与主选完全相同,等于没有备选 · 请换一个平台/系列/型号,或清空备选平台");
            return "redirect:/admin/integrations";
        }

        // ── 校验全过 → 落库 ──
        // key:留空保原值(§22.6 私密红线 · 永不回显、永不进 audit/flash)
        if (!isBlank(qwenKey))     configService.set(fid, FamilyConfigService.K_LLM_QWEN_KEY, qwenKey.trim());
        if (!isBlank(deepseekKey)) configService.set(fid, FamilyConfigService.K_LLM_DEEPSEEK_KEY, deepseekKey.trim());
        if (!isBlank(arkKey))      configService.set(fid, FamilyConfigService.K_LLM_ARK_KEY, arkKey.trim());

        writeTriple(fid, FamilyConfigService.K_LLM_PLATFORM, FamilyConfigService.K_LLM_FAMILY,
                FamilyConfigService.K_LLM_MODEL_ID, primary);
        writeTriple(fid, FamilyConfigService.K_LLM_BACKUP_PLATFORM, FamilyConfigService.K_LLM_BACKUP_FAMILY,
                FamilyConfigService.K_LLM_BACKUP_MODEL_ID, backup);   // null = 清空备选
        writeTriple(fid, FamilyConfigService.K_LLM_VISION_PLATFORM, FamilyConfigService.K_LLM_VISION_FAMILY,
                FamilyConfigService.K_LLM_VISION_MODEL_ID, vision);
        configService.set(fid, FamilyConfigService.K_LLM_VISION_ENABLED, String.valueOf(visionEnabled));

        double temp = temperature == null ? 0.5 : Math.max(0.0, Math.min(1.0, temperature));
        int mt = Math.max(500, Math.min(maxTokens, 8000));
        int ts = Math.max(5, Math.min(timeoutSeconds, 120));
        configService.set(fid, FamilyConfigService.K_LLM_TEMPERATURE, String.valueOf(temp));
        configService.set(fid, FamilyConfigService.K_LLM_MAX_TOKENS, String.valueOf(mt));
        configService.set(fid, FamilyConfigService.K_LLM_TIMEOUT_SECS, String.valueOf(ts));

        // 审计 · 只记「已配/未配」+ 调用坐标,不记 key 明文(§22.6 私密红线)
        auditLogService.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                "family_runtime_config", fid,
                "LLM 配置 · key[百炼]=" + configured(fid, FamilyConfigService.K_LLM_QWEN_KEY)
                + " · key[DeepSeek]=" + configured(fid, FamilyConfigService.K_LLM_DEEPSEEK_KEY)
                + " · key[方舟]=" + configured(fid, FamilyConfigService.K_LLM_ARK_KEY)
                + " · 主选=" + primary.label()
                + " · 备选=" + (backup == null ? "无" : backup.label())
                + " · 截图识别=" + (visionEnabled ? vision.label() : "关闭")
                + " · temperature=" + temp + " · maxTokens=" + mt + " · timeout=" + ts + "s");
        ra.addFlashAttribute("flash", "LLM 配置已保存 · 主选 " + primary.display()
                + (backup == null ? " · 无备选" : " · 备选 " + backup.display()) + " · 下次调用生效");
        return "redirect:/admin/integrations";
    }

    /**
     * 表单三元组 → {@link LlmInvocation},不合法直接抛(message 就是给用户看的文案)。
     *
     * @param requireModel 该组是否必须能定出型号(视觉关掉时为 false:允许留着半份配置)
     */
    private static LlmInvocation parseTriple(String what, String platform, String family, String modelId,
                                             LlmCatalog.Modality modality, boolean requireModel) {
        LlmCatalog.Platform p = LlmCatalog.platform(platform)
                .orElseThrow(() -> new IllegalArgumentException(what + ":请选择平台"));
        LlmCatalog.Family f = p.family(family)
                .filter(x -> x.modality() == modality)
                .orElseThrow(() -> new IllegalArgumentException(
                        what + ":「" + (family == null || family.isBlank() ? "(未选)" : family)
                        + "」不是 " + p.label() + " 的"
                        + (modality == LlmCatalog.Modality.VISION ? "视觉" : "文本") + "模型系列"));
        String m = LlmCatalog.normalizeModel(modelId);
        if (m != null && !LlmCatalog.validModel(m)) {
            // 不回显用户填的原串(可能是粘错的 key)· 只说格式要求
            throw new IllegalArgumentException(what + ":型号格式不合法 · 只允许字母/数字/点/下划线/冒号/连字符,最长 64 位");
        }
        if (m == null && requireModel && f.requiresExplicitModel()) {
            throw new IllegalArgumentException(what + ":" + p.label() + " 的「" + f.label()
                    + "」必须手工填写型号(到控制台复制接入点 ID 或模型 ID),这一家没有可预置的推荐型号");
        }
        return new LlmInvocation(p.code(), f.code(), m);
    }

    /** 写一组三元组;{@code inv} 为 null = 清空(备选可以不设) */
    private void writeTriple(long fid, String platformKey, String familyKey, String modelKey, LlmInvocation inv) {
        configService.set(fid, platformKey, inv == null ? "" : inv.platform());
        configService.set(fid, familyKey,   inv == null ? "" : inv.family());
        configService.set(fid, modelKey,    inv == null || inv.model() == null ? "" : inv.model());
    }

    private String configured(long fid, String key) {
        return configService.isPrivateKeyConfigured(fid, key) ? "已配" : "未配";
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

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

    /**
     * v0.7 FR-131 · LLM 一键测试连接 · 用<b>已保存</b>的 key 发最小探测,验证链路通不通。
     * v1.13 起<b>按平台单独测</b>,并且用「这个平台当前被选中的那个型号」发 —— 只测端点通不通
     * 意义不大,方舟最常见的失败恰恰是接入点 ID 填错(key 完全正常)。
     *
     * <p>私密红线(决策 82):绝不回显 key、绝不把 key 进 flash / audit / 日志明文;
     * 失败原因经 {@link #classifyLlmError} 归类成无敏感信息的友好文案。
     */
    @PostMapping("/llm/test")
    public String testLlm(@AuthenticationPrincipal MemberPrincipal me,
                          @RequestParam("platform") String platform,
                          RedirectAttributes ra) {
        long fid = me.getFamilyId();
        LlmCatalog.Platform p = LlmCatalog.platform(platform).orElse(null);
        if (p == null) {
            ra.addFlashAttribute("flashError", "未知平台:" + platform);
            return "redirect:/admin/integrations";
        }
        if (!configService.isPrivateKeyConfigured(fid, p.keyName())) {
            ra.addFlashAttribute("flashError", p.label() + " 未配置 Key · 请先填好并保存,再测试连接");
            return "redirect:/admin/integrations";
        }
        com.family.finance.service.checkup.llm.LlmClient client = llmRouter.clientFor(p.code()).orElse(null);
        if (client == null) {
            ra.addFlashAttribute("flashError", p.label() + " 客户端不可用");
            return "redirect:/admin/integrations";
        }
        LlmInvocation inv = probeInvocation(fid, p);
        if (inv == null) {
            ra.addFlashAttribute("flashError", p.label() + " 还没有可测的型号 · 请先在上面选好系列并填写型号(控制台复制接入点/模型 ID),保存后再测试");
            return "redirect:/admin/integrations";
        }
        String label = p.label() + " · " + inv.resolvedModel();

        boolean ok;
        String reason;
        try {
            // 最小探测:极短 prompt,验证 key→端点→型号→解析 全链路通(与业务调用走同一路径)
            String out = client.chat(inv, "你是连通性自检,无视语义,只回复两个字:ok。", "ping");
            ok = out != null && !out.isBlank();
            reason = ok ? "可用" : "返回为空";
        } catch (Exception e) {
            ok = false;
            reason = classifyLlmError(e.getMessage());
        }

        // 审计 · 不记 key 明文(§22.6 / 决策 82)· 只记调用坐标 + 结果归类
        auditLogService.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                "family_runtime_config", fid,
                "LLM 测试连接 · " + inv.label() + " · " + (ok ? "成功" : "失败:" + reason));
        if (ok) {
            ra.addFlashAttribute("flash", label + " 测试连接成功 · " + reason);
        } else {
            ra.addFlashAttribute("flashError", label + " 测试失败 · " + reason);
        }
        return "redirect:/admin/integrations";
    }

    /**
     * 挑一个坐标去探这个平台:优先<b>用户当前真选中的</b>(主 → 备 → 视觉),
     * 都没选到这家才退回该平台第一个有默认型号的文本系列。
     * 方舟这类必须手填型号的平台,没选中就返回 null —— 与其拿个瞎猜的型号去报
     * "model not found",不如直接说「先去填型号」。
     */
    private LlmInvocation probeInvocation(long fid, LlmCatalog.Platform p) {
        LlmSettings s = LlmSettings.load(configService, fid);
        java.util.List<LlmInvocation> candidates = new java.util.ArrayList<>(s.chain());
        candidates.add(s.vision());
        for (LlmInvocation inv : candidates) {
            if (p.code().equals(inv.platform()) && inv.resolvable()) return inv;
        }
        return p.firstFamily(LlmCatalog.Modality.TEXT)
                .filter(f -> !f.requiresExplicitModel())
                .map(f -> new LlmInvocation(p.code(), f.code(), null))
                .orElse(null);
    }

    /**
     * 把 LLM 调用异常 message 归类成<b>无敏感信息</b>的友好原因(绝不含 key / 不回显原始 body)。
     *
     * <p>v1.13 加了方舟那几类。<b>顺序有讲究</b>:方舟的「接入点不存在」错误码叫
     * {@code InvalidEndpointOrModel},里面带 invalid —— 放在下面的 401/403 分支后面会被
     * 归类成「Key 无效」,而这恰恰是方舟最容易踩、也最需要说清楚的一条(key 是好的,
     * 是型号填错了)。所以型号/接入点这一档必须排在凭据档前面。</p>
     */
    static String classifyLlmError(String rawMsg) {
        String m = rawMsg == null ? "" : rawMsg.toLowerCase(java.util.Locale.ROOT);
        if (m.contains("未配置") || m.contains("not configured")) return "Key 未配置";
        // ── 方舟专属:型号/接入点 与 实名认证(必须排在凭据档之前,见上方 javadoc) ──
        if (m.contains("endpoint") || m.contains("model not found") || m.contains("modelnotfound")
                || m.contains("接入点") || m.contains("does not exist"))
            return "型号或接入点不存在(方舟需到控制台复制接入点 ID / 模型 ID)";
        if (m.contains("modelnotopen") || m.contains("not activated") || m.contains("未开通") || m.contains("未订阅"))
            return "该型号未在控制台开通(先去平台开通再试)";
        if (m.contains("realname") || m.contains("real name") || m.contains("实名"))
            return "账号未完成实名认证(方舟要求实名后才能调用)";
        if (m.contains("arrearage") || m.contains("欠费") || m.contains("billoverdue") || m.contains("bill overdue")
                || m.contains("overdue"))
            return "账户欠费或账单过期";
        if (m.contains("ratelimit") || m.contains("rate limit") || m.contains("429") || m.contains("too many"))
            return "调用过于频繁(限流),稍后再试";
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
