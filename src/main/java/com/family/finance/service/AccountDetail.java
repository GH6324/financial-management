package com.family.finance.service;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.category.ProductCategory;
import com.family.finance.domain.member.Member;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * v0.2 FR-30 · 账户详情页 ViewModel · 账本视角
 *
 * 数据来自 cashFlowMapper.findAllByFamily + transferMapper.findAllByFamily +
 * snapshotMapper.findAllByFamily 的 service 层 filter。
 */
public record AccountDetail(
        Account account,
        Member owner,
        ProductCategory category,
        BigDecimal currentBalance,
        BigDecimal previousBalance,
        BigDecimal monthDelta,
        BigDecimal cumulativeNetInflow,
        BigDecimal avgMonthlyIncome,
        BigDecimal avgMonthlyExpense,
        int entryCount,
        List<TrendPoint> trend,
        List<MonthGroup> months
) {
    public String monthDeltaPctLabel() {
        if (previousBalance == null || previousBalance.signum() == 0 || monthDelta == null) return "—";
        BigDecimal pct = monthDelta.multiply(new BigDecimal("100"))
                .divide(previousBalance.abs(), 1, RoundingMode.HALF_EVEN);
        return (pct.signum() > 0 ? "+" : "") + pct.toPlainString() + "%";
    }

    public record TrendPoint(LocalDate month, BigDecimal endBalance) {}

    /**
     * 一个月的所有流水(SNAPSHOT + cash_flow + transfer_in + transfer_out 合并)
     */
    public record MonthGroup(
            LocalDate month,
            int entryCount,
            BigDecimal netDelta,
            String netDeltaLabel,
            boolean periodOpen,
            List<Entry> entries
    ) {}

    public record Entry(
            Kind kind,
            LocalDateTime occurredAt,
            BigDecimal amount,
            String amountSignedLabel,
            String label,
            String note,
            /** cash_flow.id 或 transfer.id;SNAPSHOT 为 null */
            Long sourceId,
            /** 是否可被删除(OPEN 周期 + 非 SNAPSHOT) */
            boolean deletable
    ) {}

    public enum Kind {
        SNAPSHOT, INCOME, EXPENSE, TRANSFER_IN, TRANSFER_OUT,
        /** v0.4.1 FR-52f · 股票账户估值变动事件 */
        VALUATION
    }
}
