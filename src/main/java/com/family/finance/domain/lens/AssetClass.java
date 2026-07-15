package com.family.finance.domain.lens;

import com.family.finance.domain.account.AccountType;

/**
 * v1.1 · 大类资产维度(资产透视)· prd/v1.1 FR-1。
 *
 * <p>与 {@code loanKind}/{@code InsuranceSubType} 同构:Java 枚举给下拉 + 中文 label,
 * 持久化存 {@link #name()} 到 {@code account.asset_class}(DB 不加 CHECK,加值免迁移)。
 * NULL = 按 {@link #defaultFor} 派生默认;LOAN 是负债不进资产大类(lens 不计入分母)。</p>
 */
public enum AssetClass {
    CASH_EQ("现金及等价"),
    FIXED_INCOME("固收"),
    EQUITY("权益"),
    ALTERNATIVE("另类"),
    REAL_ESTATE("不动产"),
    INSURANCE("保险");

    private final String label;

    AssetClass(String label) { this.label = label; }

    public String getLabel() { return label; }

    /** 安全解析 · 非法/空返回 null(脏值不抛) */
    public static AssetClass fromName(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** name → 中文 label · 模板/JSON 用,避免裸露 code */
    public static String labelOf(String name) {
        AssetClass c = fromName(name);
        return c == null ? "" : c.getLabel();
    }

    /**
     * 按账户类型(+ 产品类目)派生默认大类 · 用户未打标时生效,可被 account.asset_class 覆盖。
     * WEALTH 细分:货币基金 → 现金及等价;其余理财默认固收(银行理财主流形态)。
     * LOAN / 未知 → null(负债不进大类;OTHER 落「未分类」,不装懂)。
     */
    public static AssetClass defaultFor(AccountType type, String productCategoryCode) {
        if (type == null) return null;
        return switch (type) {
            case CASH -> CASH_EQ;
            case STOCK -> EQUITY;
            case CRYPTO, METAL -> ALTERNATIVE;
            case PROPERTY -> REAL_ESTATE;
            case INSURANCE -> INSURANCE;
            case WEALTH -> "MONEY_FUND".equals(productCategoryCode) ? CASH_EQ : FIXED_INCOME;
            case LOAN, OTHER -> null;
        };
    }
}
