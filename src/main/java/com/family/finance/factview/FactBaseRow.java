package com.family.finance.factview;

import java.math.BigDecimal;
import java.time.LocalDate;

public record FactBaseRow(
        Long accountId,
        String accountName,
        String accountType,
        String accountCurrency,
        Long ownerId,
        Integer displayOrder,
        Long periodId,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal previousEndBalance,
        BigDecimal endBalance,
        BigDecimal incomeOrig,
        BigDecimal expenseOrig,
        BigDecimal transferInOrig,
        BigDecimal transferOutOrig,
        BigDecimal fxToBase,
        /** v0.3.3 · pc.liquidity_class · 来自 LEFT JOIN product_category · 可空(未设类目) */
        String productLiquidityClass
) {
}
