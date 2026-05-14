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
                return DiagnoseResult.ok(hit.diagnoseText, hit.vendor, true);
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

            // 校验:LLM 输出应仍为代号体(成员A/B/C),不该含真名
            OutputValidator.Result vr = OutputValidator.check(raw, realNames);
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
                return DiagnoseResult.ok(mapped, client.vendor(), false);
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
     */
    public record DiagnoseResult(
            boolean available,
            String text,
            String vendor,
            boolean fromCache,
            Instant generatedAt
    ) {
        public static DiagnoseResult ok(String text, String vendor, boolean fromCache) {
            return new DiagnoseResult(true, text, vendor, fromCache, Instant.now());
        }
        public static DiagnoseResult unavailable(String reason) {
            return new DiagnoseResult(false,
                    "AI 综合诊断暂时不可用。以上为系统规则引擎给出的硬数据便签卡,可作为本次体检的核心参考。如需 AI 视角,请稍后刷新重试。",
                    "fallback", false, Instant.now());
        }
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
