package com.family.finance.domain.goal;

/**
 * 目标追踪指标 · v0.16。与账户/报表指标目录(MetricPrefsService.ACCOUNT/FAMILY)同源。
 *
 * <p>每个指标声明:类别(金额/比率/月数)· 是否仅家庭级(0 账户才可选)· 默认达标方向。
 * 聚合口径见 {@code GoalMetricEvaluator}:金额类 Σ、比率类价值加权、0 账户走家庭 KPI。</p>
 */
public enum GoalMetric {
    // ── 金额类(Σ 绑定账户;0 账户 = 家庭对应总量;支持 pace 时间落后判定)──
    AMOUNT_TOTAL ("金额合计",           Kind.AMOUNT, false, GoalComparator.GTE),
    CASH_TOTAL   ("现金合计",           Kind.AMOUNT, false, GoalComparator.GTE),
    NET_PRINCIPAL("累计净投入(本金)",  Kind.AMOUNT, false, GoalComparator.GTE),
    CUM_PNL      ("累计投资损益",       Kind.AMOUNT, false, GoalComparator.GTE),
    PERIOD_PNL   ("本期损益",           Kind.AMOUNT, false, GoalComparator.GTE),
    TOTAL_ASSETS ("总资产",             Kind.AMOUNT, false, GoalComparator.GTE),
    TOTAL_LIAB   ("总负债",             Kind.AMOUNT, false, GoalComparator.LTE),
    // ── 比率类(价值加权;仅倒计时,不做 pace 落后硬判)──
    RETURN_XIRR  ("年化收益率",         Kind.RATE,   false, GoalComparator.GTE),
    RETURN_BASE  ("本位币收益率(含汇率)", Kind.RATE, false, GoalComparator.GTE),
    SHARE_PCT    ("占家庭比重",         Kind.RATE,   false, GoalComparator.GTE),
    MOM_PCT      ("环比 MoM",           Kind.RATE,   false, GoalComparator.GTE),
    SAVINGS_RATE ("储蓄率",             Kind.RATE,   true,  GoalComparator.GTE),
    MAX_DRAWDOWN ("最大回撤",           Kind.RATE,   false, GoalComparator.LTE),
    // ── 月数 ──
    EMERGENCY_MONTHS("紧急储备月数",    Kind.MONTHS, true,  GoalComparator.GTE);

    public enum Kind { AMOUNT, RATE, MONTHS }

    private final String label;
    private final Kind kind;
    private final boolean familyOnly;   // 仅 0 账户(全家)可选
    private final GoalComparator defaultComparator;

    GoalMetric(String label, Kind kind, boolean familyOnly, GoalComparator defaultComparator) {
        this.label = label; this.kind = kind; this.familyOnly = familyOnly; this.defaultComparator = defaultComparator;
    }

    public String label() { return label; }
    public Kind kind() { return kind; }
    public boolean familyOnly() { return familyOnly; }
    public GoalComparator defaultComparator() { return defaultComparator; }

    /** 比率类(百分比口径)· 不参与「进度 vs 时间」落后判定(非线性累计,避免误导)。 */
    public boolean isRate() { return kind == Kind.RATE; }
    /** 金额类 · 参与 pace 时间落后判定。 */
    public boolean isAmount() { return kind == Kind.AMOUNT; }

    public static GoalMetric fromOrDefault(String name) {
        if (name == null) return AMOUNT_TOTAL;
        try { return valueOf(name.trim()); } catch (Exception e) { return AMOUNT_TOTAL; }
    }
}
