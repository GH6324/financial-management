package com.family.finance.domain.lens;

/**
 * v1.1 · 资金用途维度(评审新增)· 账户级 · 纯手标(AI 不猜个人意图)。
 * 回答「这笔钱在为什么服务」:应急金 / 教育金 / 养老储备 / 购房置业 / 长期增值 / 日常备用。
 * 存 name(),DB 不加 CHECK;NULL = 未分类。
 */
public enum PurposeTag {
    EMERGENCY("应急金", "ying ji jin"),
    EDUCATION("教育金", "jiao yu jin"),
    RETIREMENT("养老储备", "yang lao chu bei"),
    HOUSING("购房置业", "gou fang zhi ye"),
    GROWTH("长期增值", "chang qi zeng zhi"),
    DAILY("日常备用", "ri chang bei yong"),
    OTHER("其他用途", "qi ta yong tu");

    private final String label;
    private final String pinyin;   // 全拼(空格分节)· 自研下拉搜索用

    PurposeTag(String label, String pinyin) { this.label = label; this.pinyin = pinyin; }

    public String getLabel() { return label; }

    public String getPinyin() { return pinyin; }

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
