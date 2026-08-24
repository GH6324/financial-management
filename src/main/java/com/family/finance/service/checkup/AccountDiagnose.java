package com.family.finance.service.checkup;

import com.family.finance.calc.BenchmarkComparator;
import com.family.finance.calc.MaxDrawdownCalculator;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.category.ProductCategory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * 账户体检 ViewModel · v0.2 · FR-40b
 *
 * 由 {@link AccountDiagnoseService} 计算填充,模板按 {@link AccountType} 分支渲染:
 * - STOCK / WEALTH / CRYPTO:4 张投资卡 — 收益 / 风险 / 基准 / 现金流
 * - CASH:2 张储蓄卡 — 余额 / 流动性
 * - LOAN:2 张负债卡 — 本金 / 还款进度
 * - PROPERTY / OTHER:1 张简卡 — 估值
 */
public record AccountDiagnose(
        Account account,
        ProductCategory category,
        BigDecimal currentBalance,
        BigDecimal previousBalance,
        BigDecimal monthDelta,
        Integer monthsHeld,
        BigDecimal cumulativeIncome,
        BigDecimal cumulativeExpense,
        BigDecimal cumulativeTransferIn,
        BigDecimal cumulativeTransferOut,
        /** 净外部本金投入 = income - expense + transferIn - transferOut(累计) */
        BigDecimal netPrincipalInjected,
        /** 累计投资损益 = currentBalance - sum(net principal injected) */
        BigDecimal cumulativePnl,
        BigDecimal annualizedReturn,
        MaxDrawdownCalculator.Result drawdown,
        BenchmarkComparator.Result benchmark,
        Integer effectiveRiskLevel,
        boolean riskOverridden,
        List<TrendPoint> sparkline
) {
    /**
     * v1.18.5 · 收口到 {@link AccountType#isInvestment()}。
     * 原来这里写死 STOCK/WEALTH/CRYPTO —— <b>漏了 v0.14 加的 METAL</b>,
     * 于是贵金属账户被「持有期 / 收益 / 回撤」三条投资类体检规则静默跳过。
     */
    public boolean isInvestment() {
        return account.getType().isInvestment();
    }

    public boolean isCash() {
        return account.getType() == AccountType.CASH;
    }

    public boolean isLoan() {
        return account.getType().isLiability();
    }

    public boolean isProperty() {
        return account.getType() == AccountType.PROPERTY;
    }

    public String riskStars() {
        if (effectiveRiskLevel == null || effectiveRiskLevel <= 0) return "—";
        return "★".repeat(Math.min(effectiveRiskLevel, 6));
    }

    public String monthDeltaPctLabel() {
        if (previousBalance == null || previousBalance.signum() == 0 || monthDelta == null) return "—";
        BigDecimal pct = monthDelta.multiply(new BigDecimal("100"))
                .divide(previousBalance.abs(), 1, RoundingMode.HALF_EVEN);
        return (pct.signum() > 0 ? "+" : "") + pct.toPlainString() + "%";
    }

    public String annualizedReturnPctLabel() {
        if (annualizedReturn == null) return "—";
        BigDecimal pct = annualizedReturn.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_EVEN);
        return (pct.signum() > 0 ? "+" : "") + pct.toPlainString() + "%";
    }

    public record TrendPoint(LocalDate month, BigDecimal endBalance) {
    }
}
