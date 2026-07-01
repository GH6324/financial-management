package com.family.finance.service.insight;

import com.family.finance.calc.BalanceSheetHealth;
import com.family.finance.calc.BehaviorHeuristics;
import com.family.finance.calc.ConcentrationCalculator;
import com.family.finance.calc.RebalanceDrift;

import java.math.BigDecimal;
import java.util.List;

/**
 * AI 资产洞察 · v0.6 「硬数据」容器(全部由工程预计算 · LLM 只解读不计算)。
 *
 * <p>承 [[feedback_llm_no_math]]:所有 ¥/%/pp 在这里算好,喂进 prompt 让 LLM 引用。
 * 任一维度数据不足 → 该维度字段为 null(降级 · 不展示 · 不臆造)。</p>
 *
 * <p>4 主线对应体检 4 维卡:
 * <ol>
 *   <li>集中度({@link Concentration})—— 钱是不是太挤在一处</li>
 *   <li>资产负债表({@link BalanceSheetHealth.Result} + 加权负债利率)—— 金融盘 vs 不动产 · 负债率 · 提前还贷信号</li>
 *   <li>再平衡 + 行为({@link RebalanceDrift.Drift} + {@link BehaviorHeuristics.Signal})—— 偏离目标 · 追涨/从不止盈</li>
 *   <li>低利率·资产荒({@link LowRate})—— 现金占比 · 真实购买力 · 相对社会财富</li>
 * </ol>
 */
public record AssetInsight(
        Concentration concentration,
        BalanceSheetHealth.Result balanceSheet,
        BigDecimal weightedLoanRatePct,      // 加权负债利率 %(可空)
        BigDecimal assetAnnualReturnPct,     // 资产名义年化收益 %(prepay 比较基准 · 可空)
        Rebalance rebalance,
        List<BehaviorHeuristics.Signal> behaviorSignals,
        LowRate lowRate,
        int historyPeriods,                  // 月度快照期数(行为体检门槛参考)
        boolean available,
        String degradeReason
) {

    /** 集中度 4 条:房产 / 单一账户 / 单一币种敞口 / 单一标的(标的级需个股数据 · 常降级)。 */
    public record Concentration(
            BigDecimal totalAssets,
            ConcentrationCalculator.Line property,     // 房产占总资产
            String topAccountLabel,
            ConcentrationCalculator.Line topAccount,   // 最大单一账户占总资产
            String topCurrencyLabel,
            ConcentrationCalculator.Line topCurrency,  // 最大外币敞口占总资产(可空=无外币)
            BigDecimal thresholdPct                    // 参考风险线 %
    ) {}

    /** 再平衡:锚 + 4 桶偏离。 */
    public record Rebalance(
            String anchorCode,
            BigDecimal thresholdPp,
            List<RebalanceDrift.Drift> drifts
    ) {}

    /** 低利率·资产荒视角:现金占比 + 真实/相对收益(扣 CPI / M2)。 */
    public record LowRate(
            BigDecimal cashPct,              // 现金桶当前占比 %(可空)
            BigDecimal realReturnPct,        // v0.11.5:名义增长 − CPI 累计 超额 pp(可空 · 负=跑输通胀)· 仅供 prompt,洞察不展示
            BigDecimal relativeReturnPct,    // v0.11.5:名义增长 − M2 累计 超额 pp(可空 · 负=跑输社会平均)
            BigDecimal nominalGrowthPct      // v0.10.3 · 名义净资产增长 %(不扣通胀)· 洞察展示用
    ) {}

    static AssetInsight unavailable(String reason) {
        return new AssetInsight(null, null, null, null, null, List.of(), null, 0, false, reason);
    }
}
