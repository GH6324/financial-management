package com.family.finance.web.report;

import java.math.BigDecimal;

/**
 * v0.4 FR-61b · 报表账户行 · 含基准对照数据。
 *
 * @param accountName 账户显示名
 * @param accountType STOCK/WEALTH/CASH/...(label form 已含中文)
 * @param productCategoryCode 产品类目 code · 可空
 * @param xirrLabel 已格式化(+/-XX.X%)· 可空 = "—"
 * @param benchmarkPct 基准 %(年化 · 6.00 = 6%)· 可空
 * @param diffPct xirr% - benchmark% 已格式化(+/-X.XX)· 可空
 * @param beatStatus BEAT/FLAT/MISS/NA
 * @param currentValueLabel 当前价值 ¥XXX 已格式化
 */
public record AccountBenchmarkRow(
    String accountName,
    String accountType,
    String productCategoryCode,
    String xirrLabel,
    BigDecimal benchmarkPct,
    BigDecimal diffPct,
    String beatStatus,
    String currentValueLabel
) {}
