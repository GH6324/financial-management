package com.family.finance.service.analysis;

/**
 * v1.27 · 分析范围的三个取值(PRD FR-820)。
 *
 * <p>三者都用「<b>排除了谁</b>」来定义,而不是「包含谁」:新建的账户默认就在范围里,
 * 不需要谁记得把它加进去(tech-design v1.27 选型二)。</p>
 */
public enum ScopeKind {
    /** 现状:含所有账户 */
    ALL("全部资产"),
    /** 不含标了「不参与配置分析」的账户 */
    ADJUSTABLE("可调整的资产"),
    /** 不含房产类、其他类账户(保险留在里面 · PRD §13 ⑦) */
    FINANCIAL("金融资产");

    private final String label;

    ScopeKind(String label) { this.label = label; }

    public String getLabel() { return label; }

    /** 永不抛:脏值 / 空值返回 null(调用方据此回落到家庭默认) */
    public static ScopeKind parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        for (ScopeKind k : values()) {
            if (k.name().equalsIgnoreCase(raw.trim())) return k;
        }
        return null;
    }
}
