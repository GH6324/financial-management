package com.family.finance.domain.family;

/**
 * v0.4.14 FR-63a · 家庭级填报模板(全家统一 · 非成员级)。
 *
 * <p>模板只规范"何时填什么"的推荐节奏 + 填报页提示文案 · 不改 period_type
 * 机制。填报截止统一是 period.periodEnd(周期结束前必须填完)· 区别在节奏建议。
 */
public enum ReportingTemplate {

    /** T1 · 实时收入 · 月末支出(默认):工资日填收入+余额,月末倒数 2 天汇总填支出 */
    T1_REALTIME_INCOME_MONTHEND_EXPENSE(
        "实时收入 · 月末支出",
        "收入随发生填(如发薪日当天更新收入和余额);本月支出在月末倒数 2 天集中填一次。"),

    /** T2 · 月末一次清:所有收入/支出/余额都在月末倒数 2-3 天一次性填完,最省心 */
    T2_MONTHEND_BATCH(
        "月末一次清",
        "全部收入、支出、余额都在月末倒数 2-3 天一次性填完,适合流水少、记性好的家庭。"),

    /** T3 · 每周滚动:每周日复盘填本周收支,适合现金流频繁、想精细追踪的家庭 */
    T3_WEEKLY_ROLLING(
        "每周滚动",
        "每周日复盘一次,填这一周发生的收入和支出,月末已基本填完,适合流水频繁的家庭。");

    private final String displayName;
    private final String hintText;

    ReportingTemplate(String displayName, String hintText) {
        this.displayName = displayName;
        this.hintText = hintText;
    }

    public String displayName() { return displayName; }
    public String hintText() { return hintText; }

    /** 安全解析 · 未知/空 → 默认 T1(老 family 无该字段时也走 T1) */
    public static ReportingTemplate fromCode(String code) {
        if (code == null || code.isBlank()) return T1_REALTIME_INCOME_MONTHEND_EXPENSE;
        try {
            return ReportingTemplate.valueOf(code.trim());
        } catch (IllegalArgumentException e) {
            return T1_REALTIME_INCOME_MONTHEND_EXPENSE;
        }
    }
}
