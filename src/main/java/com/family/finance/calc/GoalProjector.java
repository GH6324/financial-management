package com.family.finance.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 三情景目标预测 · v0.3 FR-50 决策 22。
 *
 * <p>纯函数 · 给定 PV / monthlyContribution / target / 期望年数,
 * 输出 3 条复利曲线(乐观 8% / 中性 5% / 悲观 2%)+ 三个达成日期。</p>
 *
 * <p>FV 公式:</p>
 * <pre>
 *   FV(n) = PV × (1+r)^n + m × ((1+r/12)^(12n) − 1) / (r/12)
 * </pre>
 * 其中 r 是年利率 · n 是年数 · m 是月度供款 · PV 是当前净值。
 *
 * <p>达成日反推:二分法解 n 让 FV(n) ≥ target,搜索区间 [0, 50] 年 · 二分到月级精度。</p>
 */
public final class GoalProjector {

    /** 三情景预设年化利率 · 不允许用户改(决策 22) */
    public static final BigDecimal R_OPTIMISTIC = new BigDecimal("0.08");
    public static final BigDecimal R_NEUTRAL    = new BigDecimal("0.05");
    public static final BigDecimal R_PESSIMISTIC = new BigDecimal("0.02");

    /** 二分法搜索区间(年) */
    private static final int MAX_YEARS = 50;
    private static final int BISECT_ITERS = 60;

    private GoalProjector() {}

    /**
     * 主入口:计算三情景全部数据。
     *
     * @param pv                  当前净值(本位币 · 全资产口径或 CASH 类)
     * @param monthlyContribution 月度供款(本位币)· 0 也可(纯复利推)
     * @param target              目标值(本位币 · 通胀调整后)
     * @param projectionYears     曲线投影的年数(决定 path 长度 · 推荐 30)
     * @return 三情景结果
     */
    public static ScenarioResult project(BigDecimal pv,
                                         BigDecimal monthlyContribution,
                                         BigDecimal target,
                                         int projectionYears) {
        BigDecimal m = monthlyContribution == null ? BigDecimal.ZERO : monthlyContribution;
        BigDecimal v = pv == null ? BigDecimal.ZERO : pv;
        return new ScenarioResult(
            scenarioPath(v, m, R_OPTIMISTIC, projectionYears),
            scenarioPath(v, m, R_NEUTRAL,    projectionYears),
            scenarioPath(v, m, R_PESSIMISTIC,projectionYears),
            achievementDate(v, m, target, R_OPTIMISTIC),
            achievementDate(v, m, target, R_NEUTRAL),
            achievementDate(v, m, target, R_PESSIMISTIC)
        );
    }

    /**
     * 在指定年利率下投影 N 年的月度净值序列(从 0 起,长度 = years+1)。
     */
    public static List<BigDecimal> scenarioPath(BigDecimal pv, BigDecimal m, BigDecimal r, int years) {
        List<BigDecimal> path = new ArrayList<>(years + 1);
        double pvD = pv.doubleValue();
        double mD  = m.doubleValue();
        double rD  = r.doubleValue();
        for (int year = 0; year <= years; year++) {
            double fv = futureValue(pvD, mD, rD, year);
            path.add(BigDecimal.valueOf(fv).setScale(2, RoundingMode.HALF_EVEN));
        }
        return path;
    }

    /**
     * 达成日反推:解 n 让 FV(n) ≥ target · 二分法。
     *
     * @return 达成日期(从今天起 N 年后,N 用月精度)· 若 target 已被超越返回今天 · 若 50 年内不达成返回 null
     */
    public static LocalDate achievementDate(BigDecimal pv, BigDecimal m, BigDecimal target, BigDecimal r) {
        if (target == null || target.signum() <= 0) return LocalDate.now();
        double tgt = target.doubleValue();
        if (pv.doubleValue() >= tgt) return LocalDate.now();

        double pvD = pv.doubleValue();
        double mD  = m.doubleValue();
        double rD  = r.doubleValue();

        // 二分:lo = 0 年(FV < target),hi = MAX_YEARS(可能 < target 也可能 >=)
        if (futureValue(pvD, mD, rD, MAX_YEARS) < tgt) {
            return null; // 50 年内不达成
        }

        double lo = 0d, hi = MAX_YEARS;
        for (int i = 0; i < BISECT_ITERS; i++) {
            double mid = (lo + hi) / 2d;
            if (futureValue(pvD, mD, rD, mid) >= tgt) {
                hi = mid;
            } else {
                lo = mid;
            }
            if (hi - lo < 1d / 12d) break;
        }

        double years = hi;
        long months = Math.round(years * 12d);
        return LocalDate.now().plusMonths(months);
    }

    /**
     * FV 复利公式 · 见上文。years 可以是小数(给二分法用)。
     */
    public static double futureValue(double pv, double m, double r, double years) {
        double n = years; // 年数
        double mr = r / 12d;
        double growth = Math.pow(1d + r, n);
        double pvPart = pv * growth;
        double annuityPart;
        if (Math.abs(mr) < 1e-12) {
            annuityPart = m * 12d * n;
        } else {
            annuityPart = m * (Math.pow(1d + mr, 12d * n) - 1d) / mr;
        }
        return pvPart + annuityPart;
    }

    /**
     * 三情景预测结果。path 长度 = years + 1(年级精度 · UI 渲染足够)。
     */
    public record ScenarioResult(
        List<BigDecimal> optimisticPath,
        List<BigDecimal> neutralPath,
        List<BigDecimal> pessimisticPath,
        LocalDate optimisticDate,
        LocalDate neutralDate,
        LocalDate pessimisticDate
    ) {}
}
