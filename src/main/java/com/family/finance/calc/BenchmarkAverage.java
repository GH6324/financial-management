package com.family.finance.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * v0.5 FR-71 · CPI / M2 历史平均推导引擎(纯函数)。
 *
 * <p>三法并算(tech-design 决策 42):通胀/货币增速是复利,几何均值才是等效年率;
 * 中国 CPI 史有 1994=24.1% 这类极端年 + 1998-2002 通缩,简单平均会失真。</p>
 *
 * <ul>
 *   <li>{@link #geometricMean} 全历史几何均值</li>
 *   <li>{@link #trimmedGeometricMean} 剔除头尾极端值的几何均值(默认对比线)</li>
 *   <li>{@link #recentAverage} 近 N 年几何均值</li>
 * </ul>
 *
 * <p>输入是"年涨幅百分数"列表(如 CPI 2.4 表示 2.4%),输出同口径百分数。
 * 负值年(通缩)正常参与:(1 + r/100) 仍为正(只要 r > -100)。</p>
 */
public final class BenchmarkAverage {

    private BenchmarkAverage() {}

    /** 全历史几何均值 · (∏(1+rᵢ/100))^(1/n) − 1,再 ×100。空列表返回 0。 */
    public static BigDecimal geometricMean(List<BigDecimal> ratesPct) {
        if (ratesPct == null || ratesPct.isEmpty()) return BigDecimal.ZERO.setScale(2);
        double product = 1.0;
        int n = 0;
        for (BigDecimal r : ratesPct) {
            if (r == null) continue;
            product *= (1.0 + r.doubleValue() / 100.0);
            n++;
        }
        if (n == 0) return BigDecimal.ZERO.setScale(2);
        double g = Math.pow(product, 1.0 / n) - 1.0;
        return BigDecimal.valueOf(g * 100.0).setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * 剔除极端值后的几何均值:排序后掐掉头尾各 {@code trimFraction} 比例的年份,再几何均值。
     * trimFraction=0.1 → 各掐 10%。剔除后不足 1 项则退回全量几何均值。
     */
    public static BigDecimal trimmedGeometricMean(List<BigDecimal> ratesPct, double trimFraction) {
        if (ratesPct == null || ratesPct.isEmpty()) return BigDecimal.ZERO.setScale(2);
        List<BigDecimal> sorted = new ArrayList<>();
        for (BigDecimal r : ratesPct) if (r != null) sorted.add(r);
        if (sorted.isEmpty()) return BigDecimal.ZERO.setScale(2);
        Collections.sort(sorted);
        int drop = (int) Math.floor(sorted.size() * trimFraction);
        int from = drop, to = sorted.size() - drop;
        if (to - from < 1) return geometricMean(ratesPct);   // 剔太多 → 退全量
        return geometricMean(sorted.subList(from, to));
    }

    /** 近 N 年几何均值(输入需按时间升序;取末尾 N 项)。N≥列表长则等同全量。 */
    public static BigDecimal recentAverage(List<BigDecimal> ratesPctChronological, int n) {
        if (ratesPctChronological == null || ratesPctChronological.isEmpty()) return BigDecimal.ZERO.setScale(2);
        int size = ratesPctChronological.size();
        int from = Math.max(0, size - n);
        return geometricMean(ratesPctChronological.subList(from, size));
    }
}
