package com.family.finance.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * v0.4 FR-61a · 通胀对照线 · 把名义金额序列折算成实际购买力序列。
 *
 * <p>核心公式:`real_t = nominal_t / (1 + r)^t`,其中 r 是周期通胀率(默认按月)。</p>
 *
 * <p>设计说明:</p>
 * <ul>
 *   <li>纯函数 · 0 DB / 0 LLM · 给前端 Chart.js dataset 算 deflated 数据</li>
 *   <li>基期取序列第一个值的时间点(t=0 → divisor=1.0,即基期实际 = 名义)</li>
 *   <li>cpiPercentAnnual 入参单位:**%/年**(2.0 表示 2%)· 内部转月度复利</li>
 * </ul>
 */
public final class CpiDeflator {
    private CpiDeflator() {}

    /**
     * 用按月折算 · 序列下标 i 视作"距基期 i 个月"。
     *
     * @param nominal 名义金额序列(基期在第 0 个)· 不能 null
     * @param cpiPercentAnnual 年化通胀 %(2.0 表示 2%)· 0 或负数会返回 nominal 副本
     * @return 实际购买力序列(BigDecimal 保留 2 位小数 HALF_EVEN)
     */
    public static List<BigDecimal> deflateMonthly(List<BigDecimal> nominal, BigDecimal cpiPercentAnnual) {
        if (nominal == null) throw new IllegalArgumentException("nominal 不能 null");
        if (cpiPercentAnnual == null || cpiPercentAnnual.signum() <= 0) {
            // 0 / 负通胀 = 不折算
            return nominal.stream()
                .map(v -> v == null ? null : v.setScale(2, RoundingMode.HALF_EVEN))
                .toList();
        }
        // 月度复利率 = (1 + r_annual/100)^(1/12) - 1 · 简化用 r_annual/100/12(误差极小)
        double monthlyRate = cpiPercentAnnual.doubleValue() / 100.0 / 12.0;
        return java.util.stream.IntStream.range(0, nominal.size())
            .mapToObj(i -> {
                BigDecimal v = nominal.get(i);
                if (v == null) return null;
                double divisor = Math.pow(1.0 + monthlyRate, i);
                return v.divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_EVEN);
            })
            .toList();
    }

    /**
     * 单点 deflate · 给定名义金额 + 距基期月数 + 年通胀率 → 实际购买力。
     */
    public static BigDecimal deflateSinglePoint(BigDecimal nominal, int monthsFromBase, BigDecimal cpiPercentAnnual) {
        if (nominal == null) return null;
        if (cpiPercentAnnual == null || cpiPercentAnnual.signum() <= 0) {
            return nominal.setScale(2, RoundingMode.HALF_EVEN);
        }
        double monthlyRate = cpiPercentAnnual.doubleValue() / 100.0 / 12.0;
        double divisor = Math.pow(1.0 + monthlyRate, monthsFromBase);
        return nominal.divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_EVEN);
    }
}
