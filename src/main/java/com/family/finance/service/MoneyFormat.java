package com.family.finance.service;

import com.family.finance.domain.account.AccountType;

import java.math.BigDecimal;
import java.text.DecimalFormat;

public final class MoneyFormat {
    private static final DecimalFormat WHOLE = new DecimalFormat("#,##0");
    private static final DecimalFormat TWO_DP = new DecimalFormat("#,##0.00");

    private MoneyFormat() {
    }

    /** 仅返回币种符号(USD→$ / HKD→HK$ / 其它→¥) — 供 Thymeleaf 在 ¥ 字面之前用 */
    public static String symbol(String currency) {
        if (currency == null) return "¥";
        return switch (currency.toUpperCase()) {
            case "USD" -> "$";
            case "HKD" -> "HK$";
            default -> "¥";
        };
    }

    public static String format(String currency, BigDecimal amount) {
        if (amount == null) {
            return "— 待填";
        }
        String sign = amount.signum() < 0 ? "−" : "";
        return sign + symbol(currency) + WHOLE.format(amount.abs());
    }

    /** 含 2 位小数版,供账户详情页"当前本期余额"等精确显示场景 */
    public static String format2(String currency, BigDecimal amount) {
        if (amount == null) return "— 待填";
        String sign = amount.signum() < 0 ? "−" : "";
        return sign + symbol(currency) + TWO_DP.format(amount.abs());
    }

    public static String formatForAccount(AccountType type, String currency, BigDecimal amount) {
        if (amount == null) {
            return "— 待填";
        }
        return format(currency, amount);
    }

    /**
     * 支出金额的显示形态(v1.22)。
     *
     * <p>支出在语义上「钱出去了」,所以正数要显示成 <b>−¥499.00</b> ——
     * 模板里原来是硬拼一个 {@code '−' + format2(...)}。</p>
     *
     * <p>v1.22 之后支出可以是负数(退款冲正原样记),硬拼就会拼出
     * <b>{@code −−¥499.00}</b> 这种双负号。而且「负的支出」在语义上是
     * <b>钱回来了</b>,显示成 {@code +¥499.00} 比 {@code −¥-499.00} 或
     * {@code ¥-499.00} 都好读 —— 用户一眼能看出这是一笔退款。</p>
     *
     * <p>纯展示层:不改变任何金额本身,合计仍按代数和。</p>
     */
    public static String formatExpense(String currency, BigDecimal amount) {
        if (amount == null) return "— 待填";
        if (amount.signum() == 0) return symbol(currency) + TWO_DP.format(BigDecimal.ZERO);
        // 正数 = 花出去 → 显示负号;负数 = 退回来 → 显示加号
        String sign = amount.signum() > 0 ? "−" : "+";
        return sign + symbol(currency) + TWO_DP.format(amount.abs());
    }

    public static String formatDelta(String currency, BigDecimal amount) {
        if (amount == null) {
            return "—";
        }
        if (amount.signum() == 0) {
            return "—";
        }
        return (amount.signum() > 0 ? "+" : "") + format(currency, amount);
    }
}
