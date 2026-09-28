package com.family.finance.service.analysis;

import java.util.List;

/**
 * v1.27 · 一次 AI 分析用的「范围 + 模板 + 分析偏好」(PRD FR-846)。
 *
 * <p>三个配置类 AI(综合诊断 · 全家、资产洞察、调仓建议)按它组装提示词;页脚按它写「按「X」· 范围 · 参考了 N 条」;
 * 缓存按 {@link #fingerprint()} 分开(FR-848)。</p>
 *
 * @param scope              这次 AI 用的范围 = 模板固定的范围,否则页面上的范围(`?scope=` 或家庭默认)
 * @param template           这次用的模板
 * @param preferences        启用中的分析偏好原文(进提示词前再过真名映射)
 * @param familyStanceLabel  家里的风险偏好中文(立场「跟随」时写出来)
 * @param templateSourceName 我的模板的来源名(「基于「稳健守护」」)
 */
public record AnalysisContext(
        AnalysisScope scope,
        AnalysisTemplate template,
        List<String> preferences,
        String familyStanceLabel,
        String templateSourceName
) {
    public AnalysisContext {
        scope = scope == null ? AnalysisScope.all() : scope;
        template = template == null ? BuiltinTemplates.general() : template;
        preferences = preferences == null ? List.of() : List.copyOf(preferences);
    }

    /** 全部资产 · 综合体检 · 没偏好 —— 提示词与 v1.26 逐字相同 */
    public static AnalysisContext baseline() {
        return new AnalysisContext(AnalysisScope.all(), BuiltinTemplates.general(), List.of(), null, null);
    }

    public boolean isBaseline() {
        return scope.isAll() && template.isBaseline() && preferences.isEmpty();
    }

    /** 这次用的配置锚:模板指定的,否则 null(= 家里的) */
    public String anchorOverride() {
        return template.anchor();
    }

    /** 缓存指纹(FR-848):范围与标记集合 · 模板与它的版本 · 偏好 · 模板指定的锚 */
    public String fingerprint() {
        return scope.fingerprint() + "|" + template.versionedKey() + "|"
                + AnalysisPreferenceService.fingerprint(preferences) + "|" + (anchorOverride() == null ? "" : anchorOverride());
    }

    /** AI 结果页脚(FR-848):「按「稳健守护」· 可调整的资产(不含 自住房、车)· 参考了 2 条分析偏好」 */
    public String footerLabel() {
        StringBuilder sb = new StringBuilder("按「").append(template.name()).append("」· ").append(scope.kind().getLabel());
        if (!scope.isAll() && !scope.excludedLabel().isBlank()) {
            sb.append("(不含 ").append(scope.excludedLabel().replace(" · ", "、")).append(")");
        }
        if (!preferences.isEmpty()) sb.append(" · 参考了 ").append(preferences.size()).append(" 条分析偏好");
        return sb.toString();
    }
}
