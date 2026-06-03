package com.family.finance.factview;

import java.math.BigDecimal;

/**
 * KPI 快照 · v0.4.2 加 3 个"资产年化"字段(剔除外部现金流的纯投资指标)。
 *
 * <p>"含收入" vs "剔除收入" 二分:</p>
 * <ul>
 *   <li>含收入(XIRR · 资金加权 · v0.1)在 reports / dashboard 老 KPI 里</li>
 *   <li>剔除收入(TWR / 资产年化 · 本字段)反映真实"钱替你赚钱"能力</li>
 * </ul>
 *
 * @param netWorth 净资产 = 总资产 − 总负债
 * @param totalAssets 总资产(不含负债)
 * @param totalLiabilities 总负债(绝对值)
 * @param emergencyFundMonths 紧急储备月数 = LIQUID / 月均支出
 * @param debtToAssetRatio 负债率 = 负债 / 资产
 * @param netWorthDelta 净资产 Δ(vs 上期)
 * @param netWorthDeltaPct Δ%
 * @param monthlyPnlAmount v0.4.2 · 本月 PnL 金额(剔除外部现金流 · 纯投资变动)· 可空
 * @param monthlyInvestReturnPct v0.4.2 · 本月 PnL 占期初比率(0.028 = 2.8%)· 可空
 * @param annualizedInvestReturnPct v0.4.2 · 滚动 12 月年化纯投资收益(几何平均)· = familyTwr · 可空
 * @param ytdInvestPnl v0.4.2 · 本年累计纯投资 PnL 金额(自然年 · 剔除现金流)· 可空
 * @param liquidAssets v0.5.3 · 流动资产(LIQUID 类目期末合计 · viewCurrency)· 紧急储备分子 · 可空
 * @param avgExpense v0.5.3 · 近 12 月月均支出(PMC 优先 · viewCurrency)· 紧急储备分母 · 可空
 * @param prevNetWorth v0.5.3 · 上期期末净资产(viewCurrency)· 本月资产收益% 的"期初" · 可空
 * @param lastNetInflow v0.5.3 · 本期净流入(人赚 · PMC 优先 · viewCurrency)· 可空
 */
public record KpiSnapshot(
        BigDecimal netWorth,
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        BigDecimal emergencyFundMonths,
        BigDecimal debtToAssetRatio,
        BigDecimal netWorthDelta,
        BigDecimal netWorthDeltaPct,
        // v0.4.2 "资产年化"二分系列(剔除外部收入的纯投资视角)
        BigDecimal monthlyPnlAmount,
        BigDecimal monthlyInvestReturnPct,
        BigDecimal annualizedInvestReturnPct,
        BigDecimal ytdInvestPnl,
        // v0.5.3 · 计算口径透明化:把原本算完即弃的中间量带出来,供 tooltip 展示真实数值
        BigDecimal liquidAssets,
        BigDecimal avgExpense,
        BigDecimal prevNetWorth,
        BigDecimal lastNetInflow
) {
    /** v0.4.2 加字段时的 backward-compat 构造器 · 老调用方继续传 7 参数 */
    public KpiSnapshot(BigDecimal netWorth, BigDecimal totalAssets, BigDecimal totalLiabilities,
                       BigDecimal emergencyFundMonths, BigDecimal debtToAssetRatio,
                       BigDecimal netWorthDelta, BigDecimal netWorthDeltaPct) {
        this(netWorth, totalAssets, totalLiabilities, emergencyFundMonths, debtToAssetRatio,
             netWorthDelta, netWorthDeltaPct, null, null, null, null, null, null, null, null);
    }

    /** v0.5.3 加 4 个透明化中间量时的 backward-compat 构造器 · 老调用方继续传 11 参数 */
    public KpiSnapshot(BigDecimal netWorth, BigDecimal totalAssets, BigDecimal totalLiabilities,
                       BigDecimal emergencyFundMonths, BigDecimal debtToAssetRatio,
                       BigDecimal netWorthDelta, BigDecimal netWorthDeltaPct,
                       BigDecimal monthlyPnlAmount, BigDecimal monthlyInvestReturnPct,
                       BigDecimal annualizedInvestReturnPct, BigDecimal ytdInvestPnl) {
        this(netWorth, totalAssets, totalLiabilities, emergencyFundMonths, debtToAssetRatio,
             netWorthDelta, netWorthDeltaPct, monthlyPnlAmount, monthlyInvestReturnPct,
             annualizedInvestReturnPct, ytdInvestPnl, null, null, null, null);
    }
}
