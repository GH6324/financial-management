package com.family.finance.calc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 再平衡偏离 · v0.6 FR-106(纯函数)。
 *
 * <p>复用 v0.4 配置 diff(当前 vs 目标 4 桶 pct),判定哪些桶偏离超阈值 + 方向。
 * 治"越涨越不卖"——超配=该减,低配=该补(只给方向,不给点位/产品)。</p>
 */
public final class RebalanceDrift {

    private RebalanceDrift() {}

    /**
     * @param bucket        桶(cash/invest/property/insurance)
     * @param currentPct    当前 %
     * @param targetPct     目标 %
     * @param diffPp        当前 − 目标(百分点)
     * @param overThreshold |diffPp| &gt; 阈值
     * @param direction     OVER(超配·减)/ UNDER(低配·补)/ OK
     */
    public record Drift(String bucket, BigDecimal currentPct, BigDecimal targetPct,
                        BigDecimal diffPp, boolean overThreshold, String direction) {}

    /**
     * @param targetPct   目标配置(桶→%)
     * @param currentPct  当前配置(桶→%)
     * @param thresholdPp 触发阈值(百分点 · 如 10)
     */
    public static List<Drift> evaluate(Map<String, BigDecimal> targetPct,
                                       Map<String, BigDecimal> currentPct,
                                       BigDecimal thresholdPp) {
        List<Drift> out = new ArrayList<>();
        if (targetPct == null || targetPct.isEmpty()) return out;
        BigDecimal th = thresholdPp == null ? BigDecimal.ZERO : thresholdPp.abs();
        // 以目标桶为准遍历(顺序稳定)
        Map<String, BigDecimal> cur = currentPct == null ? new LinkedHashMap<>() : currentPct;
        for (Map.Entry<String, BigDecimal> e : targetPct.entrySet()) {
            String bucket = e.getKey();
            BigDecimal t = e.getValue() == null ? BigDecimal.ZERO : e.getValue();
            BigDecimal c = cur.getOrDefault(bucket, BigDecimal.ZERO);
            if (c == null) c = BigDecimal.ZERO;
            BigDecimal diff = c.subtract(t);
            boolean over = diff.abs().compareTo(th) > 0;
            String dir = !over ? "OK" : (diff.signum() > 0 ? "OVER" : "UNDER");
            out.add(new Drift(bucket, c, t, diff, over, dir));
        }
        return out;
    }
}
