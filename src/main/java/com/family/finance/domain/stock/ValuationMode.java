package com.family.finance.domain.stock;

/**
 * 股票持仓估值模式 · v0.3 FR-52 决策 25。
 *
 * <ul>
 *   <li>AUTO   系统每日 T+1 拉价 · 自动算市值(shares × close_price × fx)</li>
 *   <li>MANUAL 用户手填本位币市值 · 适合未上市/私募/拉不到价的持仓(如字节跳动期权)</li>
 * </ul>
 *
 * <p>同一 STOCK 账户内可混合两种持仓 · 账户余额 = SUM(AUTO 市值) + SUM(MANUAL 市值)</p>
 */
public enum ValuationMode {
    AUTO,
    MANUAL
}
