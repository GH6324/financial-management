package com.family.finance.service.macro;

import com.family.finance.calc.WaterLevelCalculator;
import com.family.finance.domain.macro.MacroBenchmark;
import com.family.finance.factview.TrendPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.5 FR-72/73 · 财富水位组装 · 把净资产趋势映射成 CPI 保命线 / M2 地位线 + 真实/相对社会收益。
 *
 * <p>逐期复利:基准线[i] = 锚 × ∏(1 + 该期所在年份年化率/100)^(该期年数)。
 * 年数 = 相邻周期 periodStart 间隔天数 / 365(月/周周期统一处理)。
 * 缺失年份(如未来年)用三法默认值(剔极端值几何均值)兜底。</p>
 */
@Service
@RequiredArgsConstructor
public class WaterLevelService {

    private final MacroBenchmarkService macroService;

    public WaterLevel compute(List<TrendPoint> trend) {
        if (trend == null || trend.size() < 2) return WaterLevel.unavailable();
        BigDecimal anchor = trend.getFirst().value();
        if (anchor == null || anchor.signum() <= 0) return WaterLevel.unavailable();

        Map<Integer, BigDecimal> cpiByYear = new HashMap<>();
        Map<Integer, BigDecimal> m2ByYear = new HashMap<>();
        for (MacroBenchmark b : macroService.all()) {
            if (b.getYear() == null) continue;
            cpiByYear.put(b.getYear(), b.getCpiHeadline());
            m2ByYear.put(b.getYear(), b.getM2Growth());
        }
        BigDecimal cpiFallback = macroService.cpiAverages().defaultValue();
        BigDecimal m2Fallback = macroService.m2Averages().defaultValue();

        List<String> labels = new ArrayList<>();
        List<BigDecimal> nominal = new ArrayList<>();
        List<BigDecimal> cpiLine = new ArrayList<>();
        List<BigDecimal> m2Line = new ArrayList<>();
        double cpiFactor = 1.0, m2Factor = 1.0;

        for (int i = 0; i < trend.size(); i++) {
            TrendPoint p = trend.get(i);
            labels.add(p.label());
            nominal.add(p.value());
            if (i > 0) {
                TrendPoint prev = trend.get(i - 1);
                double years = Math.max(0, ChronoUnit.DAYS.between(prev.periodStart(), p.periodStart())) / 365.0;
                int year = p.periodStart().getYear();
                double cpiRate = rate(cpiByYear, year, cpiFallback);
                double m2Rate = rate(m2ByYear, year, m2Fallback);
                cpiFactor *= Math.pow(1.0 + cpiRate / 100.0, years);
                m2Factor *= Math.pow(1.0 + m2Rate / 100.0, years);
            }
            cpiLine.add(anchor.multiply(BigDecimal.valueOf(cpiFactor)).setScale(2, RoundingMode.HALF_EVEN));
            m2Line.add(anchor.multiply(BigDecimal.valueOf(m2Factor)).setScale(2, RoundingMode.HALF_EVEN));
        }

        BigDecimal last = trend.getLast().value();
        BigDecimal nominalGrowthPct = last.divide(anchor, 8, RoundingMode.HALF_EVEN)
                .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal cpiCumPct = BigDecimal.valueOf((cpiFactor - 1.0) * 100.0).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal m2CumPct = BigDecimal.valueOf((m2Factor - 1.0) * 100.0).setScale(2, RoundingMode.HALF_EVEN);

        return new WaterLevel(
                labels, nominal, cpiLine, m2Line,
                WaterLevelCalculator.realReturnPct(nominalGrowthPct, cpiCumPct),
                WaterLevelCalculator.realReturnPct(nominalGrowthPct, m2CumPct),
                anchor, last, cpiLine.getLast(), m2Line.getLast(),
                last.compareTo(cpiLine.getLast()) >= 0,
                last.compareTo(m2Line.getLast()) >= 0,
                true);
    }

    private double rate(Map<Integer, BigDecimal> map, int year, BigDecimal fallback) {
        BigDecimal r = map.get(year);
        if (r == null) r = fallback;
        return r == null ? 0.0 : r.doubleValue();
    }

    /**
     * 财富水位视图数据。
     * @param aboveCpi 当前净资产 ≥ CPI 保命线(生活质量保住)
     * @param aboveM2  当前净资产 ≥ M2 地位线(社会排位上升)
     */
    public record WaterLevel(
            List<String> labels,
            List<BigDecimal> nominal,
            List<BigDecimal> cpiLine,
            List<BigDecimal> m2Line,
            BigDecimal realReturnPct,
            BigDecimal relativeReturnPct,
            BigDecimal anchor,
            BigDecimal current,
            BigDecimal cpiBaseline,
            BigDecimal m2Baseline,
            boolean aboveCpi,
            boolean aboveM2,
            boolean available) {
        static WaterLevel unavailable() {
            return new WaterLevel(List.of(), List.of(), List.of(), List.of(),
                    null, null, null, null, null, null, false, false, false);
        }
    }
}
