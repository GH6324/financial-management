package com.family.finance.calc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 行为体检 · v0.6 FR-107(纯函数 · 保守启发式)。
 *
 * <p>基于月度快照识别行为信号:顺周期加仓(近似"追涨")、集中度持续走高(近似"从不止盈")。
 * <b>保守</b>:历史不足(&lt;minPeriods)不报;只报强信号;文案是"信号/提醒"非"判定对错"
 * (承 [[feedback_llm_validator_diagnosis]] 误杀是 bug 的纪律)。</p>
 */
public final class BehaviorHeuristics {

    private BehaviorHeuristics() {}

    /** 一期:净资产 + 当期净流入(人赚口径) */
    public record Point(BigDecimal netWorth, BigDecimal netInflow) {}

    /** @param code PRO_CYCLICAL / CONCENTRATION_RISING · @param message 中立提醒文案 */
    public record Signal(String code, String message) {}

    /**
     * @param series                 月度序列(时序 · 旧→新)
     * @param topConcentrationSeries  最大集中度(如最大类目占比 %)逐期序列 · 可空(则跳过该信号)
     * @param minPeriods             少于此期数不报(默认建议 6)
     */
    public static List<Signal> detect(List<Point> series,
                                      List<BigDecimal> topConcentrationSeries,
                                      int minPeriods) {
        List<Signal> out = new ArrayList<>();
        if (series == null || series.size() < minPeriods) return out;

        // —— 顺周期加仓("追涨"):正净流入是否明显集中在"净资产高位"期 ——
        // 用中位净资产把期分高/低两组,比较两组的正净流入之和;高位≥2×低位 且 低位非主导 → 报。
        List<BigDecimal> nws = new ArrayList<>();
        for (Point p : series) if (p != null && p.netWorth() != null) nws.add(p.netWorth());
        if (nws.size() >= minPeriods) {
            BigDecimal median = median(nws);
            BigDecimal highBuy = BigDecimal.ZERO, lowBuy = BigDecimal.ZERO;
            for (Point p : series) {
                if (p == null || p.netWorth() == null || p.netInflow() == null) continue;
                if (p.netInflow().signum() <= 0) continue;          // 只看加仓(正净流入)
                if (p.netWorth().compareTo(median) >= 0) highBuy = highBuy.add(p.netInflow());
                else lowBuy = lowBuy.add(p.netInflow());
            }
            // 强信号:高位加仓 ≥ 低位的 2 倍,且高位加仓为正
            if (highBuy.signum() > 0
                    && highBuy.compareTo(lowBuy.multiply(BigDecimal.valueOf(2))) > 0
                    && highBuy.compareTo(lowBuy) > 0) {
                out.add(new Signal("PRO_CYCLICAL",
                        "近期净流入多集中在净资产高位期 —— 接近「追涨」。再平衡的纪律能对冲这种顺周期。"));
            }
        }

        // —— 集中度持续走高("从不止盈"/越滚越集中):末段单调非降且累计涨幅显著 ——
        if (topConcentrationSeries != null && topConcentrationSeries.size() >= minPeriods) {
            List<BigDecimal> c = new ArrayList<>();
            for (BigDecimal v : topConcentrationSeries) if (v != null) c.add(v);
            if (c.size() >= minPeriods) {
                boolean nonDecreasing = true;
                for (int i = 1; i < c.size(); i++) {
                    if (c.get(i).compareTo(c.get(i - 1)) < 0) { nonDecreasing = false; break; }
                }
                BigDecimal rise = c.get(c.size() - 1).subtract(c.get(0));
                // 强信号:全程未回撤 且 累计上升 ≥ 10 个百分点
                if (nonDecreasing && rise.compareTo(BigDecimal.TEN) >= 0) {
                    out.add(new Signal("CONCENTRATION_RISING",
                            "最大持仓占比持续走高、未见减仓 —— 接近「从不止盈」。盈利仓位越滚越集中会放大单一风险。"));
                }
            }
        }
        return out;
    }

    private static BigDecimal median(List<BigDecimal> vals) {
        List<BigDecimal> s = new ArrayList<>(vals);
        s.sort(BigDecimal::compareTo);
        int n = s.size();
        if (n % 2 == 1) return s.get(n / 2);
        return s.get(n / 2 - 1).add(s.get(n / 2)).divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_EVEN);
    }
}
