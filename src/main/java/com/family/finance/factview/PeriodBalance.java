package com.family.finance.factview;

import java.math.BigDecimal;

/**
 * v1.10 · 某一期的存量余额切面。
 *
 * <p>从 {@code kpis()} 里抽出来的 —— 那里只算 {@code lastPeriodId} 一期,
 * 而 v1.10 的三列对照需要 <b>逐期</b> 的净资产 / 总资产 / 总负债 / 流动资产。
 * 抽出来而不是在别处重写谓词,是为了「同一个指标只有一套算法」
 * (项目里 v1.8 支出、v1.9.4 财富水位都栽在一个指标有两套实现上)。</p>
 *
 * @param netWorth         期末净资产(全部账户带符号求和 · ASSET+/LIABILITY−)
 * @param totalAssets      ASSET 类合计
 * @param totalLiabilities LIABILITY 类合计的**绝对值**
 * @param liquidAssets     流动性 = LIQUID 的合计
 */
public record PeriodBalance(BigDecimal netWorth, BigDecimal totalAssets,
                            BigDecimal totalLiabilities, BigDecimal liquidAssets) {

    /** 资产负债率 = 总负债 ÷ 总资产;总资产为 0 → null(不给 0 也不给 ∞) */
    public BigDecimal debtToAssetRatio() {
        if (totalAssets == null || totalAssets.signum() == 0) {
            return null;
        }
        return totalLiabilities.divide(totalAssets, 6, java.math.RoundingMode.HALF_EVEN);
    }
}
