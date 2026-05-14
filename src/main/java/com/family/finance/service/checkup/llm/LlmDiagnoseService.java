package com.family.finance.service.checkup.llm;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.domain.category.ProductCategory;
import com.family.finance.domain.member.Member;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.MemberMapper;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.FamilyService;
import com.family.finance.service.ProductCategoryService;
import com.family.finance.service.checkup.AccountDiagnose;
import com.family.finance.service.checkup.FamilyDiagnose;
import com.family.finance.service.checkup.rule.Advice;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 综合智能诊断服务 · v0.2 FR-40c · 2026-05-10 修订(决策 20)
 *
 * <p>替代旧的 {@code LlmAdviceService.polish(...)} per-advice 模式。
 * 新方向:per-page 综合诊断 — 把全家完整画像 + 命中规则集合一次性送给 LLM,
 * 让它跨规则、跨账户做综合判断,返回 200-500 字综合诊断长文。
 *
 * <p>失败兜底从"静默"改为"明示":
 * 全部 client 失败时返回 {@code DiagnoseResult.unavailable(...)},前端显示
 * 「AI 暂时不可用,以下为规则硬数据」占位 + 刷新链接。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LlmDiagnoseService {

    private final List<LlmClient> clients;
    private final AuditLogService auditLogService;
    private final MemberMapper memberMapper;
    private final AccountMapper accountMapper;
    private final ProductCategoryService categoryService;
    private final FamilyService familyService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // v0.3 FR-53d · 可选注入 · 无 goal 时 null safe(v0.2 行为不变)
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.family.finance.service.goal.GoalProgressService goalProgressService;

    /** 内存 cache:key = SHA-256(prompt context),TTL 1h */
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private static final long TTL_MS = 60L * 60 * 1000;

    /**
     * 全家维度综合诊断(默认走 cache · 1h TTL · 兼容老 caller)。
     */
    public DiagnoseResult diagnoseFamily(Long familyId, Long actorMemberId,
                                          FamilyDiagnose diagnose, List<Advice> adviceList) {
        return diagnoseFamily(familyId, actorMemberId, diagnose, adviceList, false);
    }

    /**
     * 全家维度综合诊断 · forceRefresh=true 跳过 cache 直接调 LLM 并覆写 cache。
     * 失败时返回 {@link DiagnoseResult#unavailable},前端展示降级占位。
     */
    public DiagnoseResult diagnoseFamily(Long familyId, Long actorMemberId,
                                          FamilyDiagnose diagnose, List<Advice> adviceList,
                                          boolean forceRefresh) {
        try {
            String familyName = familyService.require(familyId).getName();
            List<Member> members = memberMapper.findActiveByFamily(familyId);
            PromptBuilder.NameMapping mapping = PromptBuilder.buildNameMapping(members);

            // 组装 account summaries(已应用真名映射)
            List<PromptBuilder.AccountSummary> summaries = buildAccountSummaries(familyId, mapping);

            // user prompt 不含真名 — applyMapping 已在上层处理
            String userPrompt = PromptBuilder.userPromptForFamily(
                    PromptBuilder.applyMapping(familyName, mapping.realToCodename()),
                    diagnose,
                    summaries,
                    applyMappingToAdvice(adviceList, mapping.realToCodename()),
                    mapping.realToCodename()
            );

            // v0.3 FR-53d · 注入目标相对视角段(仅当家庭已设定目标时 · 无目标家庭行为完全保留 v0.2)
            String goalSection = buildGoalSection(familyId);
            if (goalSection != null && !goalSection.isBlank()) {
                userPrompt = userPrompt + "\n\n" + goalSection;
            }

            String systemPrompt = PromptBuilder.systemPromptForDiagnose();

            // 防御深度:确保 prompt 里没有任何真名(否则就是 buildXxx 漏了字段)
            for (String real : mapping.realToCodename().keySet()) {
                if (real != null && real.length() >= 2 && userPrompt.contains(real)) {
                    log.error("LLM prompt 含真名 [{}],已 abort 调用以保护隐私", real);
                    return DiagnoseResult.unavailable("内部脱敏失败,已保护隐私");
                }
            }

            return runDiagnose(familyId, actorMemberId, "FAMILY", null,
                    systemPrompt, userPrompt, mapping.codenameToReal(),
                    mapping.realToCodename().keySet(), forceRefresh);
        } catch (Exception e) {
            log.warn("全家综合诊断失败 familyId={}: {}", familyId, e.getMessage());
            return DiagnoseResult.unavailable("内部错误: " + e.getMessage());
        }
    }

    /**
     * 账户维度综合诊断(默认走 cache · 兼容老 caller)。
     */
    public DiagnoseResult diagnoseAccount(Long familyId, Long actorMemberId,
                                           FamilyDiagnose familyDiagnose,
                                           AccountDiagnose accountDiagnose,
                                           List<Advice> adviceList) {
        return diagnoseAccount(familyId, actorMemberId, familyDiagnose, accountDiagnose, adviceList, false);
    }

    /**
     * 账户维度综合诊断 · forceRefresh=true 跳过 cache。
     */
    public DiagnoseResult diagnoseAccount(Long familyId, Long actorMemberId,
                                           FamilyDiagnose familyDiagnose,
                                           AccountDiagnose accountDiagnose,
                                           List<Advice> adviceList,
                                           boolean forceRefresh) {
        try {
            String familyName = familyService.require(familyId).getName();
            List<Member> members = memberMapper.findActiveByFamily(familyId);
            PromptBuilder.NameMapping mapping = PromptBuilder.buildNameMapping(members);

            // 此账户主理人代号
            String ownerCode = null;
            Long ownerId = accountDiagnose.account().getPrimaryOwnerMemberId();
            if (ownerId != null) {
                Member owner = members.stream().filter(m -> m.getId().equals(ownerId)).findFirst().orElse(null);
                if (owner != null && owner.getDisplayName() != null) {
                    ownerCode = mapping.realToCodename().get(owner.getDisplayName());
                }
            }

            // 给 LlmClient 看的 advice 文本是已应用真名映射的;原 advice 不动
            String userPrompt = PromptBuilder.userPromptForAccount(
                    PromptBuilder.applyMapping(familyName, mapping.realToCodename()),
                    familyDiagnose,
                    accountDiagnose,
                    applyMappingToAdvice(adviceList, mapping.realToCodename()),
                    mapping.realToCodename(),
                    ownerCode
            );
            String systemPrompt = PromptBuilder.systemPromptForDiagnose();

            // 防御深度:确保 prompt 里没有任何真名
            for (String real : mapping.realToCodename().keySet()) {
                if (real != null && real.length() >= 2 && userPrompt.contains(real)) {
                    log.error("LLM prompt 含真名 [{}],已 abort 调用以保护隐私", real);
                    return DiagnoseResult.unavailable("内部脱敏失败,已保护隐私");
                }
            }

            return runDiagnose(familyId, actorMemberId, "ACCOUNT",
                    accountDiagnose.account().getId(),
                    systemPrompt, userPrompt, mapping.codenameToReal(),
                    mapping.realToCodename().keySet(), forceRefresh);
        } catch (Exception e) {
            log.warn("账户综合诊断失败 familyId={} accountId={}: {}",
                    familyId, accountDiagnose.account().getId(), e.getMessage());
            return DiagnoseResult.unavailable("内部错误: " + e.getMessage());
        }
    }

    private DiagnoseResult runDiagnose(Long familyId, Long actorMemberId,
                                        String scope, Long entityId,
                                        String systemPrompt, String userPrompt,
                                        Map<String, String> codenameToReal,
                                        java.util.Set<String> realNames,
                                        boolean forceRefresh) {
        // DEBUG: prompt 摘要(只在 debug level 输出,生产 info level 不会显示)
        if (log.isDebugEnabled()) {
            log.debug("LLM prompt for {} entityId={}, length={}, body=\n{}", scope, entityId, userPrompt.length(), userPrompt);
        }
        // 1. 查 cache(forceRefresh 跳过)
        String cacheKey = sha256(scope + "|" + entityId + "|" + userPrompt);
        if (!forceRefresh) {
            CacheEntry hit = cache.get(cacheKey);
            if (hit != null && System.currentTimeMillis() - hit.timestamp < TTL_MS) {
                // cache 仍存 raw 字符串 · 渲染时再次解析 JSON(off-cache structured 重新建)
                DiagnoseStructured s = tryParseStructured(hit.diagnoseText);
                return DiagnoseResult.ok(hit.diagnoseText, hit.vendor, true, s);
            }
        } else {
            log.info("LLM diagnose forceRefresh · scope={} entityId={}", scope, entityId);
            cache.remove(cacheKey);
        }

        // 2. 遍历 clients(主 qwen → 备 deepseek)
        for (LlmClient client : clients) {
            if (!client.available()) continue;
            long t0 = System.currentTimeMillis();
            String raw = null;
            String error = null;
            try {
                raw = client.chat(systemPrompt, userPrompt);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                long elapsed = System.currentTimeMillis() - t0;
                log.warn("LLM[{}] 调用失败: {} (elapsed={}ms)", client.vendor(), error, elapsed);
                LlmAuditLogger.log(client.vendor(), scope, familyId, entityId,
                        systemPrompt, userPrompt, null, elapsed, false, null, error);
                continue;
            }
            long elapsed = System.currentTimeMillis() - t0;

            // 校验(JSON 模式 · 把 JSON 里的所有 string 拼起来过 validator)
            //   v0.4.9:LLM 现在输出 JSON · 把 user-facing 字段(narrative/finding/evidence/actions)
            //   join 后过 OutputValidator,行为等价于老的纯文本校验
            String textForValidate = joinUserFacingStrings(raw);
            OutputValidator.Result vr = OutputValidator.check(textForValidate, realNames);
            // 全交互日志(prompt + response + elapsed,无论接受与否都记)
            LlmAuditLogger.log(client.vendor(), scope, familyId, entityId,
                    systemPrompt, userPrompt, raw, elapsed,
                    vr.accepted(), vr.accepted() ? null : vr.reason(), null);

            if (!vr.accepted()) {
                log.warn("LLM[{}] 综合诊断输出未通过校验: {}", client.vendor(), vr.reason());
                auditLogService.record(familyId, actorMemberId, AuditLogType.LLM_REJECTED,
                        "checkup_diagnose", entityId,
                        "vendor=" + client.vendor() + " reason=" + vr.reason());
                continue;
            }
            try {

                // 反映射代号 → 真名(给前端用户展示)
                String mapped = PromptBuilder.reverseMapping(raw, codenameToReal);
                cache.put(cacheKey, new CacheEntry(mapped, client.vendor(), System.currentTimeMillis()));
                DiagnoseStructured structured = tryParseStructured(mapped);
                return DiagnoseResult.ok(mapped, client.vendor(), false, structured);
            } catch (Exception e) {
                log.warn("LLM[{}] 调用失败: {}", client.vendor(), e.getMessage());
            }
        }

        // 3. 全部失败
        try {
            auditLogService.record(familyId, actorMemberId, AuditLogType.LLM_DEGRADED,
                    "checkup_diagnose", entityId,
                    "全部 LLM client 失败/无可用,综合诊断显示降级占位");
        } catch (Exception ignore) {
            // audit 失败不阻塞主流程
        }
        return DiagnoseResult.unavailable("AI 暂时不可用");
    }

    /**
     * v0.4.9 · 尝试把 LLM 输出 raw 字符串解析成结构化对象 · 解析失败返 null(前端会 fallback 显示 text)。
     * 支持 LLM 用 markdown 包裹的 JSON(```json ... ```)· 自动剥壳。
     */
    private DiagnoseStructured tryParseStructured(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            // 1. 提取首个 JSON 对象(去 markdown ``` 包裹)
            String clean = extractJsonObject(raw);
            if (clean == null) return null;
            JsonNode root = objectMapper.readTree(clean);

            // 2. overall
            JsonNode overallNode = root.path("overall");
            if (overallNode.isMissingNode() || overallNode.isNull()) return null;
            String overallVerdict = overallNode.path("verdict").asText("STABLE");
            String overallSummary = overallNode.path("summary").asText("");

            // 3. dimensions(应有 4 条 · 但容忍 LLM 偶尔少给 1 条)
            JsonNode dimsNode = root.path("dimensions");
            if (!dimsNode.isArray() || dimsNode.isEmpty()) return null;
            List<DiagnoseStructured.Dimension> dims = new ArrayList<>();
            // 维度名 → 默认图标(若 LLM 没给)
            Map<String, String> defaultIcons = Map.of(
                "资产配置", "📊", "风险敞口", "⚡", "流动性", "💧", "收益质量", "📈"
            );
            for (JsonNode d : dimsNode) {
                String name = d.path("name").asText("");
                if (name.isBlank()) continue;
                String icon = d.path("icon").asText(defaultIcons.getOrDefault(name, "•"));
                String verdict = d.path("verdict").asText("OK");
                String finding = d.path("finding").asText("");
                String evidence = d.path("evidence").asText("");
                dims.add(new DiagnoseStructured.Dimension(name, icon, verdict, finding, evidence));
            }
            if (dims.isEmpty()) return null;

            // 4. actions(可空)
            List<String> actions = new ArrayList<>();
            JsonNode actNode = root.path("actions");
            if (actNode.isArray()) {
                for (JsonNode a : actNode) {
                    String s = a.asText("");
                    if (!s.isBlank()) actions.add(s);
                }
            }

            return new DiagnoseStructured(overallVerdict, overallSummary, dims, actions);
        } catch (Exception e) {
            log.debug("LLM 输出非结构化 JSON · fallback 到纯文本: {}", e.getMessage());
            return null;
        }
    }

    /** 提取 raw 中第一个完整的 JSON 对象 · 去 markdown 包裹 */
    private String extractJsonObject(String raw) {
        if (raw == null) return null;
        // 找首个 { 和最后一个 }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return raw.substring(start, end + 1);
    }

    /**
     * v0.4.9 · 把 JSON 输出里所有 user-facing string 拼起来给 OutputValidator 扫描。
     * 解析失败 → 直接返 raw(老路径行为)。
     */
    private String joinUserFacingStrings(String raw) {
        if (raw == null) return "";
        try {
            String clean = extractJsonObject(raw);
            if (clean == null) return raw;
            JsonNode root = objectMapper.readTree(clean);
            StringBuilder sb = new StringBuilder();
            // overall.summary
            sb.append(root.path("overall").path("summary").asText("")).append("\n");
            // dimensions[].finding + evidence
            for (JsonNode d : root.path("dimensions")) {
                sb.append(d.path("finding").asText("")).append("\n");
                sb.append(d.path("evidence").asText("")).append("\n");
            }
            // actions[]
            for (JsonNode a : root.path("actions")) {
                sb.append(a.asText("")).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception ignored) {
            return raw;
        }
    }

    private List<PromptBuilder.AccountSummary> buildAccountSummaries(Long familyId,
                                                                     PromptBuilder.NameMapping mapping) {
        List<Account> accounts = accountMapper.findActiveByFamily(familyId);
        List<Member> members = memberMapper.findActiveByFamily(familyId);
        List<PromptBuilder.AccountSummary> out = new ArrayList<>();
        for (Account a : accounts) {
            ProductCategory cat = a.getProductCategoryCode() == null ? null
                    : categoryService.findByCode(a.getProductCategoryCode()).orElse(null);
            String riskLabel;
            int level = a.getRiskLevelOverride() != null
                    ? a.getRiskLevelOverride()
                    : (cat != null ? cat.getRiskLevel() : 0);
            riskLabel = "★".repeat(Math.max(0, Math.min(level, 6))) + (level > 0 ? "" : "—");

            String ownerCode = null;
            if (a.getPrimaryOwnerMemberId() != null) {
                Member m = members.stream().filter(x -> x.getId().equals(a.getPrimaryOwnerMemberId()))
                        .findFirst().orElse(null);
                if (m != null && m.getDisplayName() != null) {
                    ownerCode = mapping.realToCodename().get(m.getDisplayName());
                }
            }

            // 此处不能调用 AccountDiagnoseService(会循环依赖 + 太重),只给基础硬事实
            // 完整 AccountDiagnose 在账户维度 prompt 中才传入
            out.add(new PromptBuilder.AccountSummary(
                    PromptBuilder.applyMapping(a.getDisplayName(), mapping.realToCodename()),
                    a.getType().name(),
                    a.getProductCategoryCode(),
                    riskLabel,
                    ownerCode,
                    null,  // currentBalance:全家维度时不传单账户余额(规则文本里已含)
                    null,
                    cat == null ? null : cat.getBenchmarkLabel(),
                    cat == null ? null : (cat.getBenchmarkPct() == null ? null
                            : cat.getBenchmarkPct().multiply(new BigDecimal("100")))
            ));
        }
        return out;
    }

    /** 把 advice 列表里的 rawTitle/rawBody 应用真名映射(防御深度) */
    private List<Advice> applyMappingToAdvice(List<Advice> list, Map<String, String> realToCodename) {
        if (realToCodename.isEmpty()) return list;
        List<Advice> out = new ArrayList<>(list.size());
        for (Advice a : list) {
            out.add(new Advice(
                    a.ruleId(), a.scope(), a.accountId(), a.dimension(), a.severity(), a.category(),
                    PromptBuilder.applyMapping(a.rawTitle(), realToCodename),
                    PromptBuilder.applyMapping(a.rawBody(), realToCodename),
                    PromptBuilder.applyMapping(a.title(), realToCodename),
                    PromptBuilder.applyMapping(a.body(), realToCodename),
                    a.cta()
            ));
        }
        return out;
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private record CacheEntry(String diagnoseText, String vendor, long timestamp) {}

    /**
     * AI 综合诊断结果。
     *
     * @param available  AI 是否可用(false 时 text 为占位文案)
     * @param text       AI 输出综合诊断长文(已反映射回真名);或降级占位
     * @param vendor     成功时:实际 LLM 厂商(qwen / deepseek);降级时为 "fallback"
     * @param fromCache  是否来自缓存
     * @param generatedAt 生成时刻(展示用)
     * @param structured v0.4.9 起 · LLM 输出结构化 JSON 时填入 · 老路径 / fallback 时为 null
     * @param truncated  v0.4.10 起 · text 看起来是被截断的 JSON 时为 true · 前端显示"输出截断 请刷新"
     */
    public record DiagnoseResult(
            boolean available,
            String text,
            String vendor,
            boolean fromCache,
            Instant generatedAt,
            DiagnoseStructured structured,
            boolean truncated
    ) {
        public static DiagnoseResult ok(String text, String vendor, boolean fromCache) {
            return new DiagnoseResult(true, text, vendor, fromCache, Instant.now(), null, false);
        }
        public static DiagnoseResult ok(String text, String vendor, boolean fromCache, DiagnoseStructured structured) {
            return new DiagnoseResult(true, text, vendor, fromCache, Instant.now(), structured,
                    structured == null && looksTruncatedJson(text));
        }
        public static DiagnoseResult unavailable(String reason) {
            return new DiagnoseResult(false,
                    "AI 综合诊断暂时不可用。以上为系统规则引擎给出的硬数据便签卡,可作为本次体检的核心参考。如需 AI 视角,请稍后刷新重试。",
                    "fallback", false, Instant.now(), null, false);
        }

        /** 启发式判断:raw 以 { 开头但未正确闭合(无 } 结尾)→ 被截断的 JSON */
        private static boolean looksTruncatedJson(String text) {
            if (text == null) return false;
            String t = text.trim();
            return t.startsWith("{") && !t.endsWith("}");
        }
    }

    /**
     * v0.4.9 · AI 诊断结构化输出(JSON 解析后)
     * <p>前端按 overall 总评 + 4 dimension 卡 + actions 列表渲染 · 替代纯文本散文。
     *
     * @param overallVerdict 总体判断 STABLE | NEEDS_ATTENTION | RISK
     * @param overallSummary 1-2 句总评(40-80 字)
     * @param dimensions     4 个诊断维度卡(配置/风险/流动性/收益)
     * @param actions        1-3 条优先行动(可执行 · 跨规则综合)
     */
    public record DiagnoseStructured(
            String overallVerdict,
            String overallSummary,
            List<Dimension> dimensions,
            List<String> actions
    ) {
        /**
         * 单个诊断维度卡。
         *
         * @param name     维度名(资产配置 / 风险敞口 / 流动性 / 收益质量)
         * @param icon     单字符 emoji(📊 / ⚡ / 💧 / 📈)
         * @param verdict  OK | WARN | RISK
         * @param finding  诊断结论(30-80 字)
         * @param evidence 数据支撑(20-50 字 · 引用上下文硬事实)
         */
        public record Dimension(
                String name,
                String icon,
                String verdict,
                String finding,
                String evidence
        ) {}
    }

    /** 透出 cache 状态(供测试用) */
    public Optional<String> peekCache(String key) {
        CacheEntry e = cache.get(key);
        return Optional.ofNullable(e == null ? null : e.diagnoseText);
    }

    /**
     * v0.3 FR-53d · 构建目标相对视角段 · 无目标时返回 null(prompt 不加段)。
     */
    private String buildGoalSection(Long familyId) {
        if (goalProgressService == null || familyId == null) return null;
        try {
            var progresses = goalProgressService.computeAll(familyId);
            if (progresses.isEmpty()) return null;
            StringBuilder sb = new StringBuilder("家庭已设定的目标(基于目标的相对视角):\n");
            for (var p : progresses) {
                String name = p.goal().getName();
                String type = p.goal().getGoalType().name();
                int pct = p.progressPct().intValue();
                String dateLabel = p.neutralDate() == null ? "未达成范围"
                    : p.neutralDate().getYear() + "(中性 5%)";
                sb.append("  - ").append(name)
                  .append("(").append(type).append(")· 进度 ").append(pct).append("% · 预计达成 ")
                  .append(dateLabel).append("\n");
            }
            sb.append("\n请评估当前资产配置是否贴合上述时间表,在综合诊断中体现「距 N 年」「股票/现金占比是否合理」等长期视角。");
            return sb.toString();
        } catch (Exception e) {
            log.warn("buildGoalSection failed (non-blocking): {}", e.toString());
            return null;
        }
    }
}
