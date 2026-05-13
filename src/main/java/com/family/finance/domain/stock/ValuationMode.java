package com.family.finance.domain.stock;

/**
 * 股票账户内"持仓行"的估值模式 · v0.3 FR-52 决策 25 + FR-52e 决策 27 (2026-05-13 追加 CASH)。
 *
 * <ul>
 *   <li>AUTO   系统每日 T+1 拉价 · shares × close_price × FX(holding.currency → account.currency)</li>
 *   <li>MANUAL 用户手填账户币种市值 · 适合未上市/私募/拉不到价(如字节跳动期权)</li>
 *   <li>CASH   账户内"现金余额"行 · 带 currency · 估值 = manual_value × FX(currency → account.currency)
 *             适合 IBKR / 富途 / 老虎 这类券商账户内的港币/美元/CNY 多币种闲置现金</li>
 * </ul>
 *
 * <p>账户余额 = SUM(AUTO 市值,FX 至账户币种)
 *           + SUM(MANUAL.manual_value,直接账户币种)
 *           + SUM(CASH.manual_value × FX(CASH.currency → account.currency))</p>
 *
 * <p>字段语义复用矩阵(stock_holding 表):</p>
 * <pre>
 *           ticker  market  shares  cost_basis  currency       manual_value     manual_value_at
 *  AUTO     必填    必填    必填    可选        必填(持仓原币种) NULL             NULL
 *  MANUAL   NULL    NULL    NULL    NULL        NULL(默认账户币种)必填(账户币种)  必填
 *  CASH     NULL    NULL    NULL    NULL        必填(现金币种)    必填(币种内金额)必填
 * </pre>
 */
public enum ValuationMode {
    AUTO,
    MANUAL,
    CASH
}
