package com.family.finance.service.checkup.rule;

/**
 * 规则引擎输出 · 一条建议 · v0.2 FR-40c
 *
 * 文案分两层:
 * - {@code rawTitle / rawBody} · 规则引擎程序生成,数字 100% 准确;LLM 永远不能改这两个
 * - {@code title / body}      · 经 LLM 文案润色后(可选)的最终呈现
 *
 * 第一次评估时 title=rawTitle, body=rawBody。LLM 润色后由 polish 端点替换 title/body。
 *
 * @param ruleId 规则代号(LIQ-1, RET-2, FAM-CON-1 ...)
 * @param scope  ACCOUNT / FAMILY
 * @param accountId 账户级建议关联的账户 id;FAMILY 级为 null
 * @param dimension 流动性 / 风险与配置 / 收益质量 / 负债健康
 * @param severity OK / INFO / WARN / DANGER
 * @param category 集中度告警 / 流动性提示 / 收益评估 / 风险调整收益 / 还款进度 ...
 * @param rawTitle 标题(规则直出,数字精确)
 * @param rawBody  正文(规则直出)
 * @param cta      可选行动按钮文案(如「划转」「设提醒」),null = 仅显示「✕ 不适用」
 * @param ctaHref  v1.27 · 行动按钮去哪;null = 老行为(账户级回账户体检页,家庭级回本页)
 */
public record Advice(
        String ruleId,
        Scope scope,
        Long accountId,
        Dimension dimension,
        Severity severity,
        String category,
        String rawTitle,
        String rawBody,
        String title,
        String body,
        String cta,
        String ctaHref
) {
    /** v1.27 之前的 11 参签名:行动按钮走老去处 */
    public Advice(String ruleId, Scope scope, Long accountId, Dimension dimension, Severity severity,
                  String category, String rawTitle, String rawBody, String title, String body, String cta) {
        this(ruleId, scope, accountId, dimension, severity, category, rawTitle, rawBody, title, body, cta, null);
    }

    /** v1.27 · 换一个行动按钮(文案 + 去处)—— FAM-CON-1 命中房产 / 其他类时直达「不参与配置分析」勾选项 */
    public Advice withCta(String newCta, String href) {
        return new Advice(ruleId, scope, accountId, dimension, severity, category,
                rawTitle, rawBody, title, body, newCta, href);
    }

    public enum Scope { ACCOUNT, FAMILY }

    public enum Severity {
        OK, INFO, WARN, DANGER;
    }

    public enum Dimension {
        LIQUIDITY("流动性"),
        RISK_ALLOCATION("风险与配置"),
        RETURN_QUALITY("收益质量"),
        DEBT_HEALTH("负债健康");

        public final String label;
        Dimension(String label) { this.label = label; }
    }

    public Advice withPolished(String newTitle, String newBody) {
        return new Advice(ruleId, scope, accountId, dimension, severity, category,
                rawTitle, rawBody, newTitle, newBody, cta, ctaHref);
    }

    public static Advice of(String ruleId, Scope scope, Long accountId, Dimension dim, Severity sev,
                            String category, String title, String body, String cta) {
        return new Advice(ruleId, scope, accountId, dim, sev, category, title, body, title, body, cta);
    }
}
