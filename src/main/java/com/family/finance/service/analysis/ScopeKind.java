package com.family.finance.service.analysis;

/**
 * v1.27 · 分析范围的三个取值(PRD FR-820)。
 *
 * <p>三者都用「<b>排除了谁</b>」来定义,而不是「包含谁」:新建的账户默认就在范围里,
 * 不需要谁记得把它加进去(tech-design v1.27 选型二)。</p>
 */
public enum ScopeKind {
    /** 现状:含所有账户 */
    ALL("全部资产", "全都算进去",
            "房子、车、存款、投资、保险全都算进占比 —— 就是原来的样子。"),
    /** 不含标了「不参与配置分析」的账户 */
    ADJUSTABLE("可调整的资产", "去掉我标过的账户",
            "只有某一套自住房、某个账户短期动不了,其余照常看,就选这个。要去掉谁,在「账户 → 编辑」里勾「不参与配置分析」。"),
    /** 不含房产类、其他类账户(保险留在里面 · PRD §13 ⑦) */
    FINANCIAL("金融资产", "不看房子和车",
            "不想看不动产的分布、房子占比太大把结论带偏了,就选这个:房产类和「其他」类(车等)都不算进占比;存款、投资、保险照常看。");

    private final String label;
    /** v1.27.1 · 给人看的叫法(维护者 2026-09-29:「范围」三个词不直观,要说「你不想看 X 就选这个」) */
    private final String plainName;
    /** v1.27.1 · 什么情况下选它 —— 定制模板页、分析设置页的选项下面各一行 */
    private final String whenToUse;

    ScopeKind(String label, String plainName, String whenToUse) {
        this.label = label;
        this.plainName = plainName;
        this.whenToUse = whenToUse;
    }

    public String getLabel() { return label; }

    public String getPlainName() { return plainName; }

    public String getWhenToUse() { return whenToUse; }

    /** 永不抛:脏值 / 空值返回 null(调用方据此回落到家庭默认) */
    public static ScopeKind parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        for (ScopeKind k : values()) {
            if (k.name().equalsIgnoreCase(raw.trim())) return k;
        }
        return null;
    }
}
