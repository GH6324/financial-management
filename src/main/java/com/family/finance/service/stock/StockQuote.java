package com.family.finance.service.stock;

import com.family.finance.domain.stock.Market;

import java.math.BigDecimal;

/**
 * 拉价结果值对象 · 一个 ticker + market 的当前/收盘价。
 *
 * @param ticker      入参 ticker(已规范化:美股大写 / A股小写市场前缀 / 港股 5 位前导零)
 * @param market      市场
 * @param closePrice  收盘价(或当前价 · 原币种)
 * @param currency    报价币种(随市场 · USD/CNY/HKD)
 * @param source      数据源标记 · "sina" | "tencent"
 */
public record StockQuote(
    String ticker,
    Market market,
    BigDecimal closePrice,
    String currency,
    String source
) {}
