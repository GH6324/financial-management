package com.family.finance.domain.family;

/**
 * v1.8 · 支出录入方式(家庭级)。
 *
 * <p>收入侧自 v0.12 起就是「逐笔 + 选账户 + 自动入账」,支出侧一直是「每人一个月一个总数」。
 * v1.8 把两侧对齐,但**不强制迁移** —— 支出笔数远多于收入,强制逐笔会直接违反
 * 「每月 10 分钟」这条产品硬约束。所以两种方式并存,由家庭自己选。</p>
 *
 * <p>默认 {@link #TOTAL} = 保持现状,老用户升级后行为完全不变。</p>
 */
public enum ExpenseEntryMode {

    /** 每人一个月填一个总数(现状 · 默认)· 写 period_member_cashflow.total_expense_input */
    TOTAL("总额", "每人每月填一个总数,从各端账单抄过来即可。省事,但没有支出构成。"),

    /** 逐笔录入并落到账户 · 写 cash_flow(EXPENSE)+ 从该账户余额扣除 */
    ITEMIZED("逐笔", "每笔选账户 + 类目,自动从该账户余额扣除。多花点时间,换来支出构成与更准的账户级收益率。");

    private final String displayName;
    private final String hintText;

    ExpenseEntryMode(String displayName, String hintText) {
        this.displayName = displayName;
        this.hintText = hintText;
    }

    public String displayName() { return displayName; }
    public String hintText() { return hintText; }

    /** 脏值 / null 一律兜底成 TOTAL —— 与 ReportingTemplate.fromCode 同一手法,不抛异常 */
    public static ExpenseEntryMode fromCode(String code) {
        if (code == null || code.isBlank()) return TOTAL;
        try {
            return ExpenseEntryMode.valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return TOTAL;
        }
    }
}
