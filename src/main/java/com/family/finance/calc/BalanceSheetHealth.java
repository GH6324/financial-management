package com.family.finance.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 资产负债表健康 · v0.6 FR-103/104(纯函数)。
 *
 * <p>金融盘 vs 不动产占比 · 负债率分级 · 负债利率 vs 资产真实收益(提前还贷信号)。</p>
 */
public final class BalanceSheetHealth {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private BalanceSheetHealth() {}

    /**
     * @param financialPct 金融盘占(金融+不动产)% · 可空
     * @param propertyPct  不动产占 % · 可空
     * @param debtRatioPct 负债率 = 总负债/总资产 % · 可空
     * @param debtBand     负债率分级:HEALTHY(&lt;30) / ELEVATED(30-50) / ALERT(&gt;50) / UNKNOWN
     * @param prepaySignal 负债利率 &gt; 资产真实收益 → true(一般"提前还贷更划算"信号)· 缺数据 null
     */
    public record Result(BigDecimal financialPct, BigDecimal propertyPct,
                         BigDecimal debtRatioPct, String debtBand, Boolean prepaySignal) {}

    public static Result evaluate(BigDecimal financial, BigDecimal property,
                                  BigDecimal liabilities, BigDecimal totalAssets,
                                  BigDecimal weightedLoanRatePct, BigDecimal assetRealReturnPct) {
        BigDecimal fp = ConcentrationCalculator.pct(financial, nz(financial).add(nz(property)));
        BigDecimal pp = ConcentrationCalculator.pct(property, nz(financial).add(nz(property)));
        BigDecimal dr = ConcentrationCalculator.pct(liabilities, totalAssets);
        String band = "UNKNOWN";
        if (dr != null) {
            if (dr.compareTo(new BigDecimal("30")) < 0) band = "HEALTHY";
            else if (dr.compareTo(new BigDecimal("50")) <= 0) band = "ELEVATED";
            else band = "ALERT";
        }
        Boolean prepay = (weightedLoanRatePct != null && assetRealReturnPct != null)
                ? weightedLoanRatePct.compareTo(assetRealReturnPct) > 0
                : null;
        return new Result(fp, pp, dr, band, prepay);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
