package com.family.finance.domain.expense;

/**
 * v1.21 · 一格金额的<b>来源</b>。
 *
 * <p>类目显示额 = Σ(各来源行)。把来源拆开存,是为了让下面这件事成立:</p>
 *
 * <pre>
 *   重导某渠道 = 只替换该渠道的行 → 手填与其它渠道【纹丝不动】
 * </pre>
 *
 * <p>如果只存合成额,重导时根本拆不出渠道份额 —— 用户的手工修正必然被冲掉,
 * 而那是最伤信任的一种失败。</p>
 */
public enum ExpenseSource {

    /** 用户在填报页手填 / 手工调整。<b>唯一允许为负的来源</b>(负值 = 对导入值的冲正)。 */
    MANUAL("手工"),
    ALIPAY("支付宝"),
    WECHAT("微信"),
    /** AI 截图转写(任何渠道的月度统计页) */
    SHOT("截图");

    private final String label;

    ExpenseSource(String label) { this.label = label; }

    public String getLabel() { return label; }

    /** 认不出的来源一律当手工 —— 宁可归到用户名下,也不要凭空多一个渠道 */
    public static ExpenseSource parse(String s) {
        if (s == null) return MANUAL;
        for (ExpenseSource v : values()) if (v.name().equalsIgnoreCase(s.trim())) return v;
        return MANUAL;
    }

    public boolean isImported() { return this != MANUAL; }
}
