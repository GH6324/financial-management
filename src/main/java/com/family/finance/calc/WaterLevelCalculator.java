package com.family.finance.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * v0.5 FR-72/73 · 财富水位计算(纯函数)。
 *
 * <p>给定区间起点净资产(锚)+ 逐期年化基准率,算出 CPI 保命线 / M2 地位线,
 * 以及真实收益 / 相对社会收益。</p>
 *
 * <p>基准线 = 锚 × 累计因子。累计因子 = ∏(1 + 该期年化率/100)^(该期年数),
 * 月周期则每期年数 = 1/12。</p>
 */
public final class WaterLevelCalculator {

    private WaterLevelCalculator() {}

    /**
     * 累计因子 · 逐期复利。
     * @param perPeriodAnnualPct 每个区间步的年化率 %(如月周期 12 步对应 12 个年化率)
     * @param yearsPerStep 每步对应的年数(月周期 = 1/12.0)
     */
    public static BigDecimal cumulativeFactor(List<BigDecimal> perPeriodAnnualPct, double yearsPerStep) {
        if (perPeriodAnnualPct == null || perPeriodAnnualPct.isEmpty()) return BigDecimal.ONE.setScale(6);
        double factor = 1.0;
        for (BigDecimal r : perPeriodAnnualPct) {
            double rate = r == null ? 0.0 : r.doubleValue();
            factor *= Math.pow(1.0 + rate / 100.0, yearsPerStep);
        }
        return BigDecimal.valueOf(factor).setScale(6, RoundingMode.HALF_EVEN);
    }

    /** 基准线某点值 = 锚 × 累计因子。 */
    public static BigDecimal baselineValue(BigDecimal anchor, BigDecimal cumulativeFactor) {
        if (anchor == null || cumulativeFactor == null) return null;
        return anchor.multiply(cumulativeFactor).setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * v0.11.5 · 超额百分点(pp)= 名义增长% − 基准累计% —— 两个比例相减,单位 pp(与 vs基准/预实 同规则)。
     *   正 = 跑赢基准(CPI/M2),负 = 跑输。用户口径:所有「两比例相比」的指标一律相减取 pp,不用 Fisher 比率。
     *   (原为 Fisher 真实收益率 (1+名义)/(1+基准)−1;v0.11.5 起改简单相减取 pp,便于横向一致与直观读数。)
     * @param nominalGrowthPct 名义净资产增长 %(期末/期初 − 1)
     * @param benchmarkCumulativePct 基准累计涨幅 %(累计因子 − 1,再 ×100)
     * @return 超额 pp(名义 − 基准);任一为 null → null
     */
    public static BigDecimal realReturnPct(BigDecimal nominalGrowthPct, BigDecimal benchmarkCumulativePct) {
        if (nominalGrowthPct == null || benchmarkCumulativePct == null) return null;
        return nominalGrowthPct.subtract(benchmarkCumulativePct).setScale(2, RoundingMode.HALF_EVEN);
    }

    /** 累计涨幅 % = (累计因子 − 1) × 100。 */
    public static BigDecimal factorToCumulativePct(BigDecimal cumulativeFactor) {
        if (cumulativeFactor == null) return null;
        return cumulativeFactor.subtract(BigDecimal.ONE)
                .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_EVEN);
    }
}
