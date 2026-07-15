package com.family.finance.domain.lens;

/**
 * v1.1 · 资金用途维度(评审新增)· 账户级 · 纯手标(AI 不猜个人意图)。
 * 回答「这笔钱在为什么服务」:应急金 / 教育金 / 养老储备 / 购房置业 / 长期增值 / 日常备用。
 * 存 name(),DB 不加 CHECK;NULL = 未分类。
 */
public enum PurposeTag {
    EMERGENCY("应急金"),
    EDUCATION("教育金"),
    RETIREMENT("养老储备"),
    HOUSING("购房置业"),
    GROWTH("长期增值"),
    DAILY("日常备用"),
    OTHER("其他用途");

    private final String label;

    PurposeTag(String label) { this.label = label; }

    public String getLabel() { return label; }

    public static PurposeTag fromName(String name) {
        if (name == null || name.isBlank()) return null;
        try { return valueOf(name.trim().toUpperCase()); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    public static String labelOf(String name) {
        PurposeTag t = fromName(name);
        return t == null ? "" : t.getLabel();
    }
}
