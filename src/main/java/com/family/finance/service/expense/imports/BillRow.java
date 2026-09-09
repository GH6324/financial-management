package com.family.finance.service.expense.imports;

import java.math.BigDecimal;

/**
 * v1.21 · 从账单 csv 解出来的一行,已归一化。
 *
 * <p>{@code channelCategory} 是<b>渠道自己的分类名</b>(支付宝有,微信没有 → 为 null);
 * {@code counterparty} + {@code goods} 用来给微信做商户关键字映射。</p>
 */
public record BillRow(String channelCategory, String counterparty, String goods,
                      String direction, BigDecimal amount, String status, String rawType) {

    /** 只有「支出」进分类统计。收入、不计收支都不算家庭消费。 */
    public boolean isExpense() { return "支出".equals(direction); }

    public boolean isRefund() {
        return status != null && (status.contains("退款") || status.contains("交易关闭"));
    }
}
