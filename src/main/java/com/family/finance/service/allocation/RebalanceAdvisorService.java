package com.family.finance.service.allocation;

import com.family.finance.calc.AllocationDiff.Bucket;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.allocation.RebalanceAdviceCache;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.member.Member;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.MemberMapper;
import com.family.finance.repository.RebalanceAdviceCacheMapper;
import com.family.finance.service.FamilyService;
import com.family.finance.service.checkup.llm.LlmClient;
import com.family.finance.service.checkup.llm.OutputValidator;
import com.family.finance.service.checkup.llm.PromptBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * v0.4 FR-62b · AI 调仓建议服务。
 *
 * <p>复用 v0.2/0.3 LlmClient + PromptBuilder + OutputValidator。</p>
 *
 * <p>节流:30 天 TTL · 同 family + anchor 30 天内返缓存。</p>
 *
 * <p>输出 JSON schema:</p>
 * <pre>{
 *   "narrative": "string · 1-3 句叙事",
 *   "actions": [{
 *     "from_account": "string · 必须是真实账户名",
 *     "to_account":   "string",
 *     "amount":       number,
 *     "reason":       "string · 简短"
 *   }]
 * }</pre>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RebalanceAdvisorService {

    private static final int CACHE_TTL_DAYS = 30;
    /** 单条 action 金额不允许超过该账户余额的此比例(防 LLM 给极端值) */
    private static final BigDecimal MAX_AMOUNT_RATIO = new BigDecimal("0.50");

    private final List<LlmClient> clients;
    private final FamilyService familyService;
    private final MemberMapper memberMapper;
    private final AccountMapper accountMapper;
    private final FactViewService factViewService;
    private final AllocationService allocationService;
    private final RebalanceAdviceCacheMapper cacheMapper;
    private final ObjectMapper objectMapper;

    /**
     * 主入口:获取调仓建议(命中 30 天缓存直接返,否则调 LLM)。
     *
     * @return AdviceResult.unavailable 表示 LLM 全部失败;否则带 actions + narrative
     */
    public AdviceResult advise(long familyId) {
        try {
            Family f = familyService.require(familyId);
            String anchor = f.getAllocationAnchor() == null ? "SP_4321" : f.getAllocationAnchor();

            // 1. 查缓存(30 天 TTL)
            Optional<RebalanceAdviceCache> cached = cacheMapper.findByFamilyAndAnchor(familyId, anchor);
            if (cached.isPresent()) {
                long days = Duration.between(cached.get().getGeneratedAt(), LocalDateTime.now()).toDays();
                if (days <= CACHE_TTL_DAYS) {
                    log.info("rebalance advice cache hit · family={} anchor={} age={}d", familyId, anchor, days);
                    return parseFromJson(cached.get().getContentJson(), cached.get().getGeneratedAt(), true);
                }
            }

            // 2. 准备 prompt 上下文
            FactSlice slice = factViewService.loadDefault(familyId);
            AllocationService.DiffResult diff = allocationService.compute(familyId, slice);
            List<Account> accounts = accountMapper.findActiveByFamily(familyId);
            List<Member> members = memberMapper.findActiveByFamily(familyId);
            PromptBuilder.NameMapping mapping = PromptBuilder.buildNameMapping(members);

            String system = """
                你是家庭资产配置顾问 · 严格按以下规则输出:
                1. 只输出 JSON 对象 · 不要 markdown 包裹 · 不要解释段
                2. JSON 必须含 narrative(1-3 句叙事)+ actions 数组(每条 from_account / to_account / amount / reason)
                3. from_account 和 to_account 必须是给定账户列表的真实名字
                4. amount 必须 ≤ from_account 余额 × 0.5(避免极端调仓)· 单位:本位币 元
                5. actions 不超过 4 条 · 优先级:最大偏离的桶
                6. 不要使用真名(成员代号已脱敏)· 不要使用具体产品代码 / 担保性词(保证 / 稳赚)
                """;
            String user = buildPrompt(f, diff, accounts, members, mapping);
            String raw = invokeWithFailover(system, user);
            if (raw == null) return AdviceResult.unavailable("LLM 全部失败");

            // 3. 校验 + 解析
            //    rebalance 这条路径的 prompt 不向 LLM 传成员信息(只传账户列表 + 4 桶配置)
            //    所以 LLM 物理上不可能输出"真名"· 真名扫描在这里 0 价值 100% 误杀风险
            //    → 传空 realNames 跳过第 4 层(v0.4.7 调整 · 解决 prod「萝卜」误杀)
            //    账户名白名单仍保留(防 PRODUCT_NAME_PATTERN 误杀「支付宝-余额宝」对自家账户的引用)
            java.util.Set<String> accountNameWhitelist = accounts.stream()
                .map(Account::getDisplayName)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
            OutputValidator.Result valid = OutputValidator.check(
                raw, java.util.Set.of(), accountNameWhitelist);
            if (!valid.accepted()) {
                log.warn("rebalance advice LLM output 校验失败: {}", valid.reason());
                return AdviceResult.unavailable("LLM 输出未通过校验:" + valid.reason());
            }

            // 4. 解析 JSON + amount sanity
            String cleanedJson = extractJsonObject(raw);
            if (cleanedJson == null) return AdviceResult.unavailable("LLM 输出非 JSON");

            JsonNode root = objectMapper.readTree(cleanedJson);
            JsonNode actionsNode = root.path("actions");
            String narrative = root.path("narrative").asText("");

            // amount sanity: 删除超额 / 账户不存在的 actions
            Map<String, BigDecimal> balByName = new HashMap<>();
            for (Account a : accounts) {
                // 用 fact 末期余额查
                BigDecimal bal = slice.rows().stream()
                    .filter(r -> Objects.equals(r.accountId(), a.getId()))
                    .filter(r -> Objects.equals(r.periodId(), slice.lastPeriodId()))
                    .map(r -> r.endBalanceBase() == null ? BigDecimal.ZERO : r.endBalanceBase())
                    .findFirst().orElse(BigDecimal.ZERO);
                balByName.put(a.getDisplayName(), bal);
            }
            java.util.List<java.util.Map<String, Object>> sanitized = new java.util.ArrayList<>();
            if (actionsNode.isArray()) {
                for (JsonNode a : actionsNode) {
                    String fromAcc = a.path("from_account").asText(null);
                    String toAcc = a.path("to_account").asText(null);
                    double amt = a.path("amount").asDouble(0);
                    String reason = a.path("reason").asText("");
                    if (fromAcc == null || toAcc == null || amt <= 0) continue;
                    BigDecimal balance = balByName.get(fromAcc);
                    if (balance == null) continue; // 账户不存在
                    BigDecimal cap = balance.multiply(MAX_AMOUNT_RATIO);
                    if (BigDecimal.valueOf(amt).compareTo(cap) > 0) {
                        amt = cap.doubleValue(); // 截断到上限
                    }
                    Map<String, Object> safe = new java.util.LinkedHashMap<>();
                    safe.put("from_account", fromAcc);
                    safe.put("to_account", toAcc);
                    safe.put("amount", (long) amt);
                    safe.put("reason", reason);
                    sanitized.add(safe);
                }
            }

            String cleanContent = objectMapper.writeValueAsString(Map.of(
                "narrative", narrative,
                "actions", sanitized));

            // 5. 写缓存
            cacheMapper.upsert(RebalanceAdviceCache.builder()
                .familyId(familyId)
                .anchorCode(anchor)
                .contentJson(cleanContent)
                .build());

            return new AdviceResult(true, narrative, sanitized, LocalDateTime.now(), false, null);
        } catch (Exception e) {
            log.warn("rebalance advise failed family={}: {}", familyId, e.toString());
            return AdviceResult.unavailable("内部错误: " + e.getMessage());
        }
    }

    /** anchor 切换或用户主动重新生成 → 删缓存 */
    public void invalidate(long familyId) {
        cacheMapper.deleteByFamily(familyId);
    }

    // ---------- 内部 ----------

    private String invokeWithFailover(String systemPrompt, String userPrompt) {
        for (LlmClient client : clients) {
            if (!client.available()) continue;
            try {
                String r = client.chat(systemPrompt, userPrompt);
                if (r != null && !r.isBlank()) return r;
            } catch (Exception e) {
                log.warn("client {} failed: {}", client.vendor(), e.toString());
            }
        }
        return null;
    }

    private String buildPrompt(Family f, AllocationService.DiffResult diff,
                               List<Account> accounts, List<Member> members,
                               PromptBuilder.NameMapping mapping) {
        StringBuilder sb = new StringBuilder();
        sb.append("家庭基础:\n");
        sb.append("- 风险偏好: ").append(f.getRiskAppetite()).append("\n");
        sb.append("- 本位币: ").append(f.getBaseCurrency()).append("\n");
        sb.append("- 当前选模板: ").append(diff.anchorCode()).append("\n\n");

        sb.append("4 类目配置(% · 当前 vs 目标 vs 偏离):\n");
        for (Bucket b : Bucket.values()) {
            sb.append("- ").append(bucketCn(b)).append(": ")
              .append(diff.currentPct().get(b)).append("% vs ")
              .append(diff.targetPct().get(b)).append("% (")
              .append(formatSigned(diff.diffPct().get(b))).append("%)\n");
        }
        sb.append("\n各账户当前余额(本位币 · 优先按 product_category 已映射 4 桶):\n");
        for (Account a : accounts) {
            sb.append("- ").append(a.getDisplayName())
              .append(" (").append(a.getType())
              .append(", 类目=").append(a.getProductCategoryCode() == null ? "未设" : a.getProductCategoryCode())
              .append(")\n");
        }
        sb.append("\n请基于 4 桶偏离 + 上述账户列表,给出 2-4 个具体调仓 action · 输出严格 JSON。\n");
        return sb.toString();
    }

    private String bucketCn(Bucket b) {
        return switch (b) {
            case CASH -> "现金";
            case INVEST -> "投资";
            case PROPERTY -> "房产";
            case INSURANCE -> "保险";
        };
    }

    private String formatSigned(BigDecimal v) {
        if (v == null) return "—";
        if (v.signum() > 0) return "+" + v.toPlainString();
        return v.toPlainString();
    }

    /** LLM 输出可能被 markdown 包裹 · 提取首个 {...} 对象 */
    private String extractJsonObject(String raw) {
        if (raw == null) return null;
        int s = raw.indexOf('{');
        int e = raw.lastIndexOf('}');
        if (s < 0 || e <= s) return null;
        return raw.substring(s, e + 1);
    }

    private AdviceResult parseFromJson(String json, LocalDateTime generatedAt, boolean fromCache) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String narrative = root.path("narrative").asText("");
            java.util.List<java.util.Map<String, Object>> actions = new java.util.ArrayList<>();
            JsonNode actionsNode = root.path("actions");
            if (actionsNode.isArray()) {
                for (JsonNode a : actionsNode) {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("from_account", a.path("from_account").asText(""));
                    m.put("to_account", a.path("to_account").asText(""));
                    m.put("amount", a.path("amount").asLong(0));
                    m.put("reason", a.path("reason").asText(""));
                    actions.add(m);
                }
            }
            return new AdviceResult(true, narrative, actions, generatedAt, fromCache, null);
        } catch (Exception e) {
            return AdviceResult.unavailable("缓存 JSON 解析失败: " + e.getMessage());
        }
    }

    /**
     * 调仓建议结果。
     */
    public record AdviceResult(
        boolean ok,
        String narrative,
        java.util.List<java.util.Map<String, Object>> actions,
        LocalDateTime generatedAt,
        boolean fromCache,
        String errorReason
    ) {
        public static AdviceResult unavailable(String reason) {
            return new AdviceResult(false, null, java.util.List.of(), null, false, reason);
        }
    }
}
