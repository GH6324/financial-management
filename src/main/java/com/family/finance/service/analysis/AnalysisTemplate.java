package com.family.finance.service.analysis;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.27 · 分析模板:AI 从什么角度看、侧重什么、口吻多稳(PRD §3.3)。
 *
 * <p><b>只改 AI 的说法</b> —— 不改任何数字、不改规则卡片(FR-840)。由几项<b>结构化设置</b>组成,
 * 不是一段提示词(FR-842);「不许算数、数字引用格式、合规底线」留在各 AI 自己的系统提示词里,模板改不到(FR-844)。</p>
 *
 * @param key           内置 = {@code GENERAL} 等;我的模板 = {@code custom:<id>}
 * @param builtin       内置模板不能改、不能删
 * @param suitsWho      一句「适合谁」;我的模板为 null
 * @param focus         侧重维度(1–4 个)
 * @param stance        立场
 * @param scope         固定的范围;null = 跟随家里的默认
 * @param anchor        指定的配置锚;null = 跟随家里的
 * @param extra         补充要求(≤ 500 字);null = 没有
 * @param version       内置:随代码改动递增;我的模板:每保存一次 +1 —— 缓存键带它
 * @param sourceKey     「基于它定制」的来源;内置为 null
 * @param sourceVersion 复制时来源的版本(只作记录,不联动 —— 内置升级不会改动副本)
 */
public record AnalysisTemplate(
        String key,
        String name,
        boolean builtin,
        String suitsWho,
        List<Focus> focus,
        Stance stance,
        ScopeKind scope,
        String anchor,
        String extra,
        int version,
        String sourceKey,
        Integer sourceVersion,
        Long updatedByMemberId,
        LocalDateTime updatedAt
) {
    public static final int MAX_EXTRA_CHARS = 500;
    public static final int MAX_FOCUS = 4;
    public static final int MAX_NAME_CHARS = 40;

    public AnalysisTemplate {
        focus = focus == null ? List.of() : List.copyOf(focus);
        stance = stance == null ? Stance.FOLLOW : stance;
    }

    /** 缓存键用:改一次就是另一份结论 */
    public String versionedKey() { return key + "@" + version; }

    /** 综合体检 = v1.26 的分析角度:提示词里不加任何模板段(FR-846 逐字相同) */
    public boolean isBaseline() { return builtin && BuiltinTemplates.GENERAL.equals(key); }

    public boolean isCustom() { return !builtin; }

    public Long customId() {
        if (builtin || key == null || !key.startsWith(BuiltinTemplates.CUSTOM_PREFIX)) return null;
        try { return Long.valueOf(key.substring(BuiltinTemplates.CUSTOM_PREFIX.length())); }
        catch (NumberFormatException e) { return null; }
    }

    public String focusLabel() {
        List<String> ls = new ArrayList<>();
        for (Focus f : focus) ls.add(f.getLabel());
        return String.join("、", ls);
    }

    /** 侧重维度 */
    public enum Focus {
        ALLOCATION("配置"),
        RISK("风险"),
        LIQUIDITY("流动性"),
        RETURN("收益"),
        CONCENTRATION("集中度"),
        DEBT("负债与现金流");

        private final String label;
        Focus(String label) { this.label = label; }
        public String getLabel() { return label; }

        /** CSV → 列表;未知值丢掉,去重保序,最多 4 个 */
        public static List<Focus> parseCsv(String csv) {
            List<Focus> out = new ArrayList<>();
            if (csv == null) return out;
            for (String s : csv.split(",")) {
                for (Focus f : values()) {
                    if (f.name().equalsIgnoreCase(s.trim()) && !out.contains(f) && out.size() < MAX_FOCUS) out.add(f);
                }
            }
            return out;
        }

        public static String toCsv(List<Focus> fs) {
            List<String> names = new ArrayList<>();
            if (fs != null) for (Focus f : fs) names.add(f.name());
            return String.join(",", names);
        }
    }

    /** 立场 */
    public enum Stance {
        FOLLOW("跟随家里的风险偏好"),
        CONSERVATIVE("稳健"),
        BALANCED("平衡"),
        AGGRESSIVE("进取");

        private final String label;
        Stance(String label) { this.label = label; }
        public String getLabel() { return label; }

        public static Stance parse(String raw) {
            if (raw == null) return FOLLOW;
            for (Stance s : values()) if (s.name().equalsIgnoreCase(raw.trim())) return s;
            return FOLLOW;
        }
    }
}
