package com.family.finance.factview;

import com.family.finance.domain.account.AccountType;

import java.math.BigDecimal;
import java.util.List;

/**
 * 账户级绩效快照(本位币口径,派生自 FactSlice · 实时算不落库)。
 * v0.8:从「当前价值/XIRR/sparkline」扩成账户级指标全集,端到 dashboard 列表 + 手机卡片。
 * 账户/品类相关字段(预期收益、风险、本位币年化)在 P2/P3 填充,P1 留 null。
 */
public record AccountPerformance(
        Long accountId,
        String accountName,
        AccountType accountType,
        String accountCurrency,
        BigDecimal currentValue,        // 当前价值(本位币)
        BigDecimal xirr,                // 年化收益率 · 原币口径(剔除汇率)— 现有
        List<TrendPoint> sparkline,     // 月末余额序列(本位币)
        // ── v0.8 P1 扩展(本位币 / 派生)──
        BigDecimal cumPnl,              // 累计投资损益
        BigDecimal netPrincipal,        // 累计净投入(本金)= Σ(income−expense+transferIn−transferOut)
        BigDecimal latestPnl,           // 本期(as-of 期)投资损益
        BigDecimal momAmount,           // 较上一账期 余额变化(金额)
        BigDecimal momPct,              // 较上一账期 %
        BigDecimal sharePct,            // 占家庭净资产 %
        BigDecimal maxDrawdownPct,      // 最大回撤 %(负数;不足 2 期为 null)
        Integer monthsHeld,             // 已记录期数
        String sparklinePoints,         // 归一化 SVG polyline points(viewBox 0 0 80 22);点不足为 null
        String sparklineTrend,          // up / down / flat / none
        // ── v0.8 P3:计算正确性 + 预实 ──
        BigDecimal returnBase,          // 本位币年化(含 FX)· Problem C;本位币账户 == xirr
        BigDecimal expectedReturnPct,   // 预期年化 %(账户覆盖 or 品类 benchmark)· 预实
        BigDecimal planActualDiffPct    // 实际 − 预期(百分点;正=跑赢)· 预实;null=未设预期/数据不足
) {
    /** 仅基础字段的工厂(派生字段留 null)· 测试/简单场景用,隔离未来字段增减。 */
    public static AccountPerformance basic(Long accountId, String accountName, AccountType accountType,
                                           String accountCurrency, BigDecimal currentValue, BigDecimal xirr,
                                           List<TrendPoint> sparkline) {
        return new AccountPerformance(accountId, accountName, accountType, accountCurrency,
                currentValue, xirr, sparkline,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null);
    }
}
