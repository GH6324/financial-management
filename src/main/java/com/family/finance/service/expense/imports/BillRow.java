package com.family.finance.service.expense.imports;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * v1.21 · 从账单 csv 解出来的<b>一笔</b>,已归一化。
 *
 * <p>{@code channelCategory} 是<b>渠道自己的分类名</b>(支付宝有,微信没有 → 为 null);
 * {@code counterparty} + {@code goods} 用来做商户关键字映射与 AI 归类。</p>
 *
 * <p>{@code payMethod} 是<b>资金来源</b>(支付宝「收/付款方式」、微信「支付方式」):
 * 余额宝 / 花呗 / 招商银行储蓄卡(1234) …… <b>一份账单里这一列是变化的</b>,
 * 所以「整批落到同一个账户」是错的 —— 每一笔各自推荐账户,用户可改(FR-575)。</p>
 *
 * <p>{@code txNo} 是渠道交易号 —— 重复导入时靠它跳过(FR-560 ⑤)。
 * 有些行没有交易号(渠道格式变动、或者那一列被裁掉),此时为 null:
 * <b>不能因此拒绝导入</b>,只是这一笔无法参与去重,重导会出双份。
 * 所以确认页要显示「其中 N 笔没有交易号」—— 让人知道重导的代价。</p>
 */
public record BillRow(String channelCategory, String counterparty, String goods,
                      String direction, BigDecimal amount, String status, String rawType,
                      LocalDate occurredAt, String txNo, String payMethod) {

    /** 只有「支出」进消费统计。收入、不计收支都不是家庭消费。 */
    public boolean isExpense() { return "支出".equals(direction); }

    public boolean isIncome() { return "收入".equals(direction); }

    /**
     * 退款 / 交易关闭 —— 这笔钱实际没花出去。
     *
     * <p>不剔除的后果是把用户的支出算高,而且高得毫无规律(退款笔数每月不同)。</p>
     */
    public boolean isRefund() {
        return status != null && (status.contains("退款") || status.contains("交易关闭"));
    }

    /**
     * 「不计收支」—— 渠道自己都说这不是收支。
     *
     * <p>典型是余额宝转入转出、还信用卡、理财买入赎回:那是<b>划转</b>,钱还在你自己名下。
     * 当成支出就等于把同一笔钱花两遍(账户余额那边已经反映了这次移动)。</p>
     */
    public boolean isNeutral() { return "不计收支".equals(direction); }

    /** 用来喂关键字规则与 AI 的那一段文本 */
    public String merchantText() {
        String a = counterparty == null ? "" : counterparty.trim();
        String b = goods == null ? "" : goods.trim();
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + " " + b;
    }
}
