package com.family.finance.factview;

import java.math.BigDecimal;

/**
 * 家庭净资产的环比(MoM:as-of vs 上一账期)/ 同比(YoY:as-of vs 去年同月账期)。
 * v0.8 · 实时算不落库;对比账期缺失(数据不足)对应字段为 null,UI 显「数据不足」。
 */
public record MomYoy(
        BigDecimal netWorth,    // as-of 期净资产(本位币)
        BigDecimal momAmount,   // 环比金额变化;null=无上期
        BigDecimal momPct,      // 环比 %
        BigDecimal yoyAmount,   // 同比金额变化;null=无去年同月
        BigDecimal yoyPct       // 同比 %
) {
}
