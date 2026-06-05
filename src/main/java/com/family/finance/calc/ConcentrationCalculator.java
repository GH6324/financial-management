package com.family.finance.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * 集中度计算 · v0.6 FR-100(纯函数 · 无 Spring · 可单测)。
 *
 * <p>"钱是不是太挤在一处":房产 / 单一标的 / 单一账户 / 单一币种 占比,对照参考风险线。
 * 全部 part ÷ whole × 100;whole≤0 → null(降级,不显该项)。</p>
 */
public final class ConcentrationCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private ConcentrationCalculator() {}

    /** part ÷ whole × 100(1 位)· whole≤0 或入参 null → null */
    public static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        if (part == null || whole == null || whole.signum() <= 0) return null;
        return part.abs().multiply(HUNDRED).divide(whole, 1, RoundingMode.HALF_UP);
    }

    /** 一组份额里"最大单个"占 whole 的比例(空/whole≤0 → null) */
    public static BigDecimal topPct(Collection<BigDecimal> parts, BigDecimal whole) {
        if (parts == null || parts.isEmpty() || whole == null || whole.signum() <= 0) return null;
        BigDecimal max = null;
        for (BigDecimal p : parts) {
            if (p == null) continue;
            BigDecimal a = p.abs();
            if (max == null || a.compareTo(max) > 0) max = a;
        }
        return pct(max, whole);
    }

    /**
     * 一条集中度结论。
     * @param pct       占比 %(可空 = 降级)
     * @param threshold 参考风险线 %(可空)
     * @param overLine  pct &gt; threshold(超线 = 偏高)
     */
    public record Line(BigDecimal pct, BigDecimal threshold, boolean overLine) {}

    /** 组装一条:算占比 + 是否超线 */
    public static Line line(BigDecimal part, BigDecimal whole, BigDecimal threshold) {
        BigDecimal p = pct(part, whole);
        boolean over = p != null && threshold != null && p.compareTo(threshold) > 0;
        return new Line(p, threshold, over);
    }
}
