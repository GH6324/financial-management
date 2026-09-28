package com.family.finance.service.analysis;

import com.family.finance.service.analysis.AnalysisTemplate.Focus;
import com.family.finance.service.analysis.AnalysisTemplate.Stance;

import java.util.List;
import java.util.Optional;

/**
 * v1.27 · 内置 5 个常见分析模板(PRD FR-841 · §13 ⑮)。
 *
 * <p><b>随代码走,不进库</b>(tech-design v1.27 选型三):回滚 jar 就回滚了内置模板,
 * 组合出的行为总是测过的那一套。改了某个内置模板的设置 → 把它的 {@code version} 加一,
 * 缓存就不会拿旧结论;用户「基于它定制」出来的副本不受影响(存的是完整副本)。</p>
 *
 * <p>覆盖四类最常见的诉求:求稳 / 求增长 / 只管投资 / 有负债;「退休规划」「子女教育」
 * 和目标页的进度计算强相关,等目标那块单独讨论时再加(PRD §13 ⑭ ⑮)。</p>
 */
public final class BuiltinTemplates {

    private BuiltinTemplates() {}

    public static final String GENERAL = "GENERAL";
    public static final String STEADY = "STEADY";
    public static final String GROWTH = "GROWTH";
    public static final String PORTFOLIO = "PORTFOLIO";
    public static final String DEBT = "DEBT";

    /** 我的模板的 key 前缀 */
    public static final String CUSTOM_PREFIX = "custom:";

    public static final List<AnalysisTemplate> ALL = List.of(
            builtin(GENERAL, "综合体检", 1,
                    "适合大多数家庭:配置、风险、流动性、收益四个方面都看一遍",
                    List.of(Focus.ALLOCATION, Focus.RISK, Focus.LIQUIDITY, Focus.RETURN), Stance.FOLLOW, null),
            builtin(STEADY, "稳健守护", 1,
                    "适合求稳、临近退休或近期有大额支出的家庭:先看本金安全、应急金够不够、风险有没有过度集中",
                    List.of(Focus.RISK, Focus.LIQUIDITY, Focus.CONCENTRATION), Stance.CONSERVATIVE, null),
            builtin(GROWTH, "积极增长", 1,
                    "适合收入稳定、能承受波动的家庭:先看收益、股票类占比、分散得够不够",
                    List.of(Focus.RETURN, Focus.ALLOCATION, Focus.CONCENTRATION), Stance.AGGRESSIVE, null),
            builtin(PORTFOLIO, "投资组合体检", 1,
                    "只看金融资产:股票、基金、理财之间怎么分,行业和平台有没有过度集中",
                    List.of(Focus.ALLOCATION, Focus.CONCENTRATION, Focus.RETURN), Stance.FOLLOW, ScopeKind.FINANCIAL),
            builtin(DEBT, "负债与现金流", 1,
                    "适合有房贷、车贷的家庭:负债率、每月还款压力、要不要提前还贷、应急金够不够",
                    List.of(Focus.DEBT, Focus.LIQUIDITY), Stance.FOLLOW, null)
    );

    public static Optional<AnalysisTemplate> find(String key) {
        if (key == null) return Optional.empty();
        return ALL.stream().filter(t -> t.key().equalsIgnoreCase(key.trim())).findFirst();
    }

    public static AnalysisTemplate general() {
        return ALL.get(0);
    }

    private static AnalysisTemplate builtin(String key, String name, int version, String suits,
                                            List<Focus> focus, Stance stance, ScopeKind scope) {
        return new AnalysisTemplate(key, name, true, suits, focus, stance, scope, null, null,
                version, null, null, null, null);
    }
}
