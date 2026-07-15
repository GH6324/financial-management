package com.family.finance.domain.account;

/**
 * v0.17 · 储蓄/理财型保险子类型 · issue #6。
 *
 * <p>仅"展示 + 分组"用途,不改变任何估值/分桶逻辑 —— 所有子类型都是"手填现金价值"资产账户。
 * 与 {@code loan_kind} 同构:Java 枚举提供下拉选项 + 中文 label,持久化存 {@link #name()} 到
 * {@code account_insurance_policy.insurance_kind}(DB 不加 CHECK,未来加子类型免迁移)。</p>
 *
 * <p>消费型保险(车险 / 医疗 / 定期寿)无现金价值 = 纯支出,<b>不建资产账户</b>,不在此列。</p>
 */
public enum InsuranceSubType {
    ANNUITY("年金险"),
    WHOLE_LIFE("增额终身寿"),
    UNIVERSAL("万能险"),
    PARTICIPATING("分红险"),
    INVESTMENT_LINKED("投连险"),
    OTHER_SAVINGS("其他储蓄型");

    private final String label;

    InsuranceSubType(String label) { this.label = label; }

    public String getLabel() { return label; }

    /** 安全解析 · 非法/空返回 null(供旁表脏值兜底,不抛) */
    public static InsuranceSubType fromName(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** name → 中文 label · 模板直接用,避免裸露 code */
    public static String labelOf(String name) {
        InsuranceSubType t = fromName(name);
        return t == null ? "" : t.getLabel();
    }
}
