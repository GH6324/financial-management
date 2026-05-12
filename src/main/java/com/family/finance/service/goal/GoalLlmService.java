package com.family.finance.service.goal;

import com.family.finance.domain.goal.Goal;
import com.family.finance.domain.goal.GoalParams;
import com.family.finance.domain.goal.GoalType;
import com.family.finance.domain.member.Member;
import com.family.finance.factview.FactViewService;
import com.family.finance.factview.KpiSnapshot;
import com.family.finance.repository.MemberMapper;
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
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 目标场景 LLM · v0.3 FR-53a/b/c。
 *
 * <p>复用 v0.2 LlmClient 接口 + PromptBuilder 真名脱敏。
 * 3 个场景:</p>
 * <ul>
 *   <li>FR-53a recommendParams · 目标创建向导 · 用户 click [🤖 AI 推荐] 时实时调用</li>
 *   <li>FR-53b generateMonthlyReport · 周期关闭后异步生成 · 写 goal_ai_report 表</li>
 *   <li>FR-53c generateAlertAdvice · 偏离预警 · 90 天节流</li>
 * </ul>
 *
 * <p>全部失败容忍 · 走 {@link AiResult#unavailable} · 上游显占位文案。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GoalLlmService {

    private final List<LlmClient> clients;
    private final FamilyService familyService;
    private final MemberMapper memberMapper;
    private final FactViewService factViewService;
    private final ObjectMapper objectMapper;

    // ---------- FR-53a 目标向导 AI ----------

    /**
     * 给指定类型推荐参数 · 返回 GoalParams(用户在表单上看到默认值后可调整再保存)。
     */
    public AiResult<GoalParams> recommendParams(long familyId, GoalType type) {
        try {
            KpiSnapshot kpis = factViewService.kpis(factViewService.loadDefault(familyId));
            List<Member> members = memberMapper.findActiveByFamily(familyId);
            PromptBuilder.NameMapping mapping = PromptBuilder.buildNameMapping(members);

            String system = """
                你是家庭财务规划助手 · 严格按以下规则输出:
                1. 只输出 JSON 对象 · 不要 markdown 包裹 · 不要解释
                2. 字段范围必须合理(年龄 18-80 / 月支出 1000-100000 / 比率 0.0-0.1)
                3. 不要使用真名 · 不要使用具体产品名 · 不要担保性词汇(保证 / 稳赚 / 一定)
                """;
            String user = buildRecommendPrompt(type, kpis, members, mapping);
            String raw = invokeWithFailover(system, user);
            if (raw == null) return AiResult.unavailable("LLM 全部失败");

            GoalParams params = parseRecommendation(type, raw);
            if (params == null) return AiResult.unavailable("LLM 输出解析失败");
            return AiResult.ok(params, extractRationale(raw));
        } catch (Exception e) {
            log.warn("recommendParams failed · type={} family={}: {}", type, familyId, e.toString());
            return AiResult.unavailable("内部错误: " + e.getMessage());
        }
    }

    // ---------- FR-53b 月报 ----------

    /**
     * 给目标生成本周期月报叙事 · 150-300 字。
     */
    public AiResult<String> generateMonthlyReport(long familyId, Goal goal,
                                                  GoalProgressService.GoalProgress progress) {
        try {
            List<Member> members = memberMapper.findActiveByFamily(familyId);
            PromptBuilder.NameMapping mapping = PromptBuilder.buildNameMapping(members);

            String system = """
                你是家庭财务规划助手 · 撰写本月目标进度叙事:
                1. 150-300 字 · 一段话或最多 3 段
                2. 不使用真名(用 成员A / 成员B 代号)· 不担保 · 不推荐具体产品
                3. 聚焦本月进度变化 + 节奏点评 + 1 个可执行建议
                """;
            String user = buildMonthlyReportPrompt(goal, progress);
            String raw = invokeWithFailover(system, user);
            if (raw == null) return AiResult.unavailable("LLM 全部失败");

            // 反向映射 codename → real name(本期 v0.3 简化:不做反向映射,prompt 内已用 codename)
            OutputValidator.Result valid = OutputValidator.check(raw, mapping.realToCodename().keySet());
            if (!valid.accepted()) {
                log.warn("monthly report rejected by validator: {}", valid.reason());
                return AiResult.unavailable("AI 输出未通过校验:" + valid.reason());
            }
            return AiResult.ok(raw.trim(), null);
        } catch (Exception e) {
            log.warn("generateMonthlyReport failed: {}", e.toString());
            return AiResult.unavailable("内部错误: " + e.getMessage());
        }
    }

    // ---------- FR-53c 偏离预警 ----------

    public AiResult<String> generateAlertAdvice(long familyId, Goal goal,
                                                GoalProgressService.GoalProgress progress,
                                                String alertReason) {
        try {
            List<Member> members = memberMapper.findActiveByFamily(familyId);
            PromptBuilder.NameMapping mapping = PromptBuilder.buildNameMapping(members);

            String system = """
                你是家庭财务规划助手 · 撰写目标偏离预警:
                1. 200-300 字 · 朱印感(严肃但不焦虑)
                2. 不使用真名 · 不担保 · 不推荐具体产品
                3. 给出具体调整方案 1-2 条(增加月供 / 调整账户配置 / 重设目标参数)
                """;
            String user = buildAlertPrompt(goal, progress, alertReason);
            String raw = invokeWithFailover(system, user);
            if (raw == null) return AiResult.unavailable("LLM 全部失败");

            OutputValidator.Result valid = OutputValidator.check(raw, mapping.realToCodename().keySet());
            if (!valid.accepted()) {
                return AiResult.unavailable("AI 输出未通过校验:" + valid.reason());
            }
            return AiResult.ok(raw.trim(), null);
        } catch (Exception e) {
            log.warn("generateAlertAdvice failed: {}", e.toString());
            return AiResult.unavailable("内部错误: " + e.getMessage());
        }
    }

    // ---------- 内部 ----------

    private String invokeWithFailover(String systemPrompt, String userPrompt) {
        for (LlmClient client : clients) {
            if (!client.available()) {
                log.debug("client {} unavailable · skip", client.vendor());
                continue;
            }
            try {
                String r = client.chat(systemPrompt, userPrompt);
                if (r != null && !r.isBlank()) return r;
            } catch (Exception e) {
                log.warn("client {} failed: {}", client.vendor(), e.toString());
            }
        }
        return null;
    }

    private String buildRecommendPrompt(GoalType type, KpiSnapshot kpis,
                                        List<Member> members, PromptBuilder.NameMapping mapping) {
        String memberInfo = members.stream()
            .limit(3)
            .map(m -> mapping.realToCodename().getOrDefault(m.getDisplayName(), m.getDisplayName())
                    + "(" + (m.getRoleLabel() == null ? "成员" : m.getRoleLabel()) + ")")
            .reduce((a, b) -> a + "、" + b).orElse("(无)");

        String kpiSummary = "净资产 ¥" + (kpis.netWorth() == null ? "?" : kpis.netWorth().setScale(0, RoundingMode.HALF_UP).toPlainString())
                          + " · 总资产 ¥" + (kpis.totalAssets() == null ? "?" : kpis.totalAssets().setScale(0, RoundingMode.HALF_UP).toPlainString())
                          + " · 总负债 ¥" + (kpis.totalLiabilities() == null ? "?" : kpis.totalLiabilities().setScale(0, RoundingMode.HALF_UP).toPlainString());

        return switch (type) {
            case RETIREMENT -> """
                家庭基础:%s · %s
                请为 [退休 / FIRE] 目标推荐合理参数 · 输出严格 JSON:
                {"retire_age": 60, "current_age": 38, "monthly_expense": 15000, "inflation_rate": 0.025, "withdrawal_rate": 0.04, "rationale": "<60 字中文理由>"}
                """.formatted(memberInfo, kpiSummary);
            case EDUCATION -> """
                家庭基础:%s · %s
                请为 [子女教育金] 目标推荐合理参数 · 输出严格 JSON:
                {"child_birth_year": 2020, "target_year_offset": 18, "target_amount": 800000, "inflation_rate": 0.03, "rationale": "<60 字中文理由>"}
                """.formatted(memberInfo, kpiSummary);
            case EMERGENCY -> """
                家庭基础:%s · %s
                请为 [应急储备] 目标推荐合理参数 · 输出严格 JSON:
                {"months_target": 6, "auto_baseline": true, "rationale": "<60 字中文理由>"}
                """.formatted(memberInfo, kpiSummary);
        };
    }

    private String buildMonthlyReportPrompt(Goal goal, GoalProgressService.GoalProgress p) {
        BigDecimal pv = p.pv();
        BigDecimal target = p.target();
        BigDecimal progress = p.progress() == null ? BigDecimal.ZERO : p.progress();
        return """
            目标:%s(类型 %s)
            当前进度:%.0f%% · 已积累 ¥%s / 目标 ¥%s
            月储蓄能力(6 期中位):¥%s
            中性情景达成日:%s

            请生成本月叙事(150-300 字)· 含进度变化点评 + 1 条可执行建议。
            """.formatted(goal.getName(), goal.getGoalType(),
                progress.movePointRight(2).doubleValue(),
                pv == null ? "?" : pv.setScale(0, RoundingMode.HALF_UP).toPlainString(),
                target == null ? "?(应急 derived)" : target.setScale(0, RoundingMode.HALF_UP).toPlainString(),
                p.monthlyContribution() == null ? "?" : p.monthlyContribution().setScale(0, RoundingMode.HALF_UP).toPlainString(),
                p.neutralDate() == null ? "未达成范围" : p.neutralDate().toString());
    }

    private String buildAlertPrompt(Goal goal, GoalProgressService.GoalProgress p, String reason) {
        return """
            目标:%s(类型 %s)· 当前进度 %.0f%%
            预警原因:%s

            请生成偏离预警 + 调整建议(200-300 字)· 不担保 · 不推荐具体产品。
            """.formatted(goal.getName(), goal.getGoalType(),
                p.progress() == null ? 0d : p.progress().movePointRight(2).doubleValue(),
                reason);
    }

    /**
     * 从 LLM 原文中提取 JSON 对象 · 容忍 markdown ```json 包裹 / 前后多余空白。
     */
    private GoalParams parseRecommendation(GoalType type, String raw) {
        String json = extractJson(raw);
        if (json == null) return null;
        try {
            JsonNode node = objectMapper.readTree(json);
            GoalParams p = new GoalParams();
            if (type == GoalType.RETIREMENT) {
                if (node.hasNonNull("retire_age")) p.setRetireAge(node.get("retire_age").asInt());
                if (node.hasNonNull("current_age")) p.setCurrentAge(node.get("current_age").asInt());
                if (node.hasNonNull("monthly_expense")) p.setMonthlyExpense(new BigDecimal(node.get("monthly_expense").asText()));
                if (node.hasNonNull("inflation_rate")) p.setInflationRate(new BigDecimal(node.get("inflation_rate").asText()));
                if (node.hasNonNull("withdrawal_rate")) p.setWithdrawalRate(new BigDecimal(node.get("withdrawal_rate").asText()));
                if (!validRetirement(p)) return null;
            } else if (type == GoalType.EDUCATION) {
                if (node.hasNonNull("child_birth_year")) p.setChildBirthYear(node.get("child_birth_year").asInt());
                if (node.hasNonNull("target_year_offset")) p.setTargetYearOffset(node.get("target_year_offset").asInt());
                if (node.hasNonNull("target_amount")) p.setTargetAmount(new BigDecimal(node.get("target_amount").asText()));
                if (node.hasNonNull("inflation_rate")) p.setInflationRate(new BigDecimal(node.get("inflation_rate").asText()));
                if (!validEducation(p)) return null;
            } else { // EMERGENCY
                if (node.hasNonNull("months_target")) p.setMonthsTarget(node.get("months_target").asInt());
                if (node.hasNonNull("auto_baseline")) p.setAutoBaseline(node.get("auto_baseline").asBoolean(true));
                if (!validEmergency(p)) return null;
            }
            return p;
        } catch (Exception e) {
            log.warn("parseRecommendation failed: {}", e.toString());
            return null;
        }
    }

    private static final Pattern JSON_RE = Pattern.compile("\\{[\\s\\S]*\\}");

    private static String extractJson(String raw) {
        if (raw == null) return null;
        // 移除 ```json ... ``` 包裹
        String cleaned = raw.replaceAll("(?s)```(?:json)?\\s*", "").replaceAll("```", "");
        Matcher m = JSON_RE.matcher(cleaned);
        return m.find() ? m.group() : null;
    }

    private String extractRationale(String raw) {
        try {
            String json = extractJson(raw);
            if (json == null) return null;
            JsonNode n = objectMapper.readTree(json);
            return n.hasNonNull("rationale") ? n.get("rationale").asText() : null;
        } catch (Exception e) { return null; }
    }

    private static boolean validRetirement(GoalParams p) {
        return p.getCurrentAge() != null && p.getCurrentAge() >= 18 && p.getCurrentAge() <= 80
            && p.getRetireAge() != null && p.getRetireAge() > p.getCurrentAge() && p.getRetireAge() <= 90
            && p.getMonthlyExpense() != null && p.getMonthlyExpense().compareTo(new BigDecimal("1000")) >= 0
                                              && p.getMonthlyExpense().compareTo(new BigDecimal("1000000")) <= 0;
    }
    private static boolean validEducation(GoalParams p) {
        return p.getTargetAmount() != null && p.getTargetAmount().compareTo(new BigDecimal("10000")) >= 0
                                            && p.getTargetAmount().compareTo(new BigDecimal("10000000")) <= 0;
    }
    private static boolean validEmergency(GoalParams p) {
        return p.getMonthsTarget() != null && p.getMonthsTarget() >= 1 && p.getMonthsTarget() <= 24;
    }

    /**
     * AI 调用结果 · 成功携带 value · 失败携带原因。
     */
    public record AiResult<T>(boolean ok, T value, String rationale, String error) {
        public static <T> AiResult<T> ok(T value, String rationale) {
            return new AiResult<>(true, value, rationale, null);
        }
        public static <T> AiResult<T> unavailable(String error) {
            return new AiResult<>(false, null, null, error);
        }
    }
}
