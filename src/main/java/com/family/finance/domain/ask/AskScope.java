package com.family.finance.domain.ask;

/**
 * v1.19 · 接入凭据的数据范围。
 *
 * <p><b>默认是 {@link #AGGREGATE}</b> —— 数据最小化:大多数问题(「钱都在哪些平台」
 * 「房产占比高不高」「应急金够几个月」)靠聚合与占比就能答,不需要账户名和逐笔流水。
 * 只有真的要下钻到某个账户时才需要 {@link #DETAIL}。</p>
 *
 * <p>这不是「权限分级」的形式主义 —— 它直接决定<b>凭据泄露时对方能读到什么</b>。</p>
 */
public enum AskScope {
    /** 聚合与占比 · 不含账户名、不含流水 */
    AGGREGATE("aggregate", "只给汇总"),
    /** 含账户名与流水明细 */
    DETAIL("detail", "含账户明细");

    private final String code;
    private final String label;

    AskScope(String code, String label) { this.code = code; this.label = label; }

    public String getCode() { return code; }
    public String getLabel() { return label; }

    /** 覆盖判断:DETAIL 覆盖 AGGREGATE,反之不行。 */
    public boolean covers(AskScope required) {
        return this == DETAIL || this == required;
    }

    /** 永不抛 —— 脏数据一律回落到最保守的那一档。 */
    public static AskScope parse(String raw) {
        if (raw != null) {
            for (AskScope s : values()) {
                if (s.code.equalsIgnoreCase(raw.trim())) return s;
            }
        }
        return AGGREGATE;
    }
}
