package com.family.finance.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * v0.4.2 · 资产年化(剔除外部现金流的纯投资收益)· "钱赚的"指标。
 *
 * <p>核心区分:</p>
 * <ul>
 *   <li>含收入指标(XIRR)→ 把工资 / 消费 / 转账都当作"现金流"投入计算 → 工资高的家庭虚高 ↑</li>
 *   <li>剔除收入指标(TWR · 本类)→ 只看资产本身的升值/贬值 → 真实"钱替你赚钱"的能力</li>
 * </ul>
 *
 * <p>月度公式:`月度收益率 = (期末净资产 − 期初净资产 − 本月净流入) / 期初净资产`</p>
 *
 * <p>年度公式:`年化 = ∏(1 + 月度r) − 1`(几何平均 · 与 TWR 定义一致)· 12 月以上才年化</p>
 *
 * <p>设计选择(2026-05-14 用户拍板):</p>
 * <ul>
 *   <li>年化用**滚动 12 月**(不卡自然年 · 避免 1 月突兀)</li>
 *   <li>月度版**不年化** · 直接显本月 ±X%</li>
 *   <li>本月期初净资产 = 上月末 snapshot(简单 · 与现金流校验等式对齐)</li>
 * </ul>
 *
 * <p>不重写 TWR 算法 · 委托 {@link TwrCalculator}(已有 v0.1 实现验证过)· 本类只做"上下文友好"封装。</p>
 */
public final class InvestmentReturnCalculator {
    private InvestmentReturnCalculator() {}

    /**
     * 月度纯投资收益。
     *
     * @param startValue 期初净资产(上月末 snapshot)
     * @param endValue 期末净资产(本月末 snapshot)
     * @param netInflow 本月净流入(income − expense + 净 transfer 跨账户已抵消)
     * @return MonthlyResult · 永不返 null · startValue ≤ 0 / 输入空 → 全 null
     */
    public static MonthlyResult monthly(BigDecimal startValue, BigDecimal endValue, BigDecimal netInflow) {
        if (startValue == null || endValue == null
            || startValue.signum() <= 0) {
            return new MonthlyResult(null, null);
        }
        BigDecimal inflow = nz(netInflow);
        BigDecimal pnlAmount = endValue.subtract(inflow).subtract(startValue);
        BigDecimal pnlPct = pnlAmount.divide(startValue, 8, RoundingMode.HALF_EVEN);
        return new MonthlyResult(
            pnlAmount.setScale(2, RoundingMode.HALF_EVEN),
            pnlPct
        );
    }

    /**
     * 滚动 12 月年化(几何平均)· 委托 TwrCalculator。
     *
     * <p>< 12 月时返累计(不年化)· 与 TwrCalculator.annualized 行为对齐。</p>
     *
     * @param series 月度点序列(顺序无关紧要,计算复用 TwrCalculator)
     * @return 12 月年化(0.072 = 7.2%)· 不足 12 月返累计 · 无数据 null
     */
    public static BigDecimal annualizedRolling12(List<TwrCalculator.TwrPoint> series) {
        if (series == null || series.isEmpty()) return null;
        return TwrCalculator.annualizedOrCumulative(series, series.size());
    }

    /**
     * 本年度(自然年)累计纯投资 PnL · 给 YTD 视角用。
     *
     * <p>简单累加各月 PnL(每月用上述公式算)。</p>
     */
    public static BigDecimal ytdPnlAmount(List<TwrCalculator.TwrPoint> currentYearMonths) {
        if (currentYearMonths == null || currentYearMonths.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (TwrCalculator.TwrPoint p : currentYearMonths) {
            if (p.startValue() == null || p.endValue() == null) continue;
            BigDecimal pnl = p.endValue().subtract(nz(p.netInflow())).subtract(p.startValue());
            sum = sum.add(pnl);
        }
        return sum.setScale(2, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * 月度结果。
     *
     * @param pnlAmount 本月 PnL 金额(可正可负 · 元 · null=数据不足)
     * @param pnlPct    本月 PnL 占期初比率(小数 · 0.028 = 2.8% · null=数据不足)
     */
    public record MonthlyResult(BigDecimal pnlAmount, BigDecimal pnlPct) {
        public boolean isPositive() {
            return pnlAmount != null && pnlAmount.signum() > 0;
        }
        public boolean isNegative() {
            return pnlAmount != null && pnlAmount.signum() < 0;
        }
    }

    /**
     * 包装单月数据 · 给单测和 service 共用。
     */
    public static Optional<TwrCalculator.TwrPoint> toPoint(
            BigDecimal startValue, BigDecimal endValue, BigDecimal netInflow) {
        if (startValue == null || endValue == null || startValue.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(new TwrCalculator.TwrPoint(startValue, endValue, nz(netInflow)));
    }
}
