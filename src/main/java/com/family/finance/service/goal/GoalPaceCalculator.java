package com.family.finance.service.goal;

import com.family.finance.domain.goal.GoalComparator;
import com.family.finance.domain.goal.GoalMetric;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * v0.16 · 目标进度与「进度 vs 时间」判定(纯函数 · today 入参便于单测)。
 *
 * <p>达标率 attain = 当前/目标(LTE 收敛类取反向);时间进度 time = 已过/总时长。
 * pace 状态只对<b>金额类 + 截止型</b>判(比率类非线性累计,只给倒计时 · FR-16-4b)。</p>
 */
public final class GoalPaceCalculator {

    private GoalPaceCalculator() {}

    /** 落后阈值:达标率落后时间进度超过该百分点 → BEHIND(硬编码,不开放配置)。 */
    static final double BEHIND_GAP = 0.15;

    public enum Status { ACHIEVED, AHEAD, ON_TRACK, BEHIND, NONE }

    /**
     * @param attainPct 达标率 [0,∞)(1.0 = 100%)
     * @param timePct   时间进度 [0,1]（长期型为 null）
     * @param daysLeft  距截止日天数(长期型为 null;已过期为负)
     * @param status    进度状态
     */
    public record Pace(BigDecimal attainPct, BigDecimal timePct, Long daysLeft, Status status) {}

    public static Pace compute(GoalMetric metric, GoalComparator cmp,
                               BigDecimal current, BigDecimal target,
                               BigDecimal baseline,        // LTE 起点(可空)
                               LocalDate created, LocalDate deadline, LocalDate today) {
        BigDecimal attain = attain(cmp, current, target, baseline);
        Long daysLeft = deadline == null ? null : ChronoUnit.DAYS.between(today, deadline);
        BigDecimal timePct = timePct(created, deadline, today);

        Status status;
        if (attain != null && attain.compareTo(BigDecimal.ONE) >= 0) {
            status = Status.ACHIEVED;
        } else if (deadline == null || !metric.isAmount() || timePct == null) {
            // 长期型 / 比率类 → 不做时间落后判定
            status = Status.NONE;
        } else {
            double a = attain == null ? 0 : attain.doubleValue();
            double t = timePct.doubleValue();
            if (a >= t) status = Status.AHEAD;
            else if (t - a > BEHIND_GAP) status = Status.BEHIND;
            else status = Status.ON_TRACK;
        }
        return new Pace(scale(attain), scale(timePct), daysLeft, status);
    }

    /** 达标率:GTE = 当前/目标;LTE = (起点−当前)/(起点−目标),无起点时退化为 目标/当前。 */
    static BigDecimal attain(GoalComparator cmp, BigDecimal current, BigDecimal target, BigDecimal baseline) {
        if (target == null || current == null) return BigDecimal.ZERO;
        if (cmp == GoalComparator.LTE) {
            if (current.compareTo(target) <= 0) return BigDecimal.ONE; // 已收敛到目标以下
            if (baseline != null && baseline.compareTo(target) > 0) {
                BigDecimal denom = baseline.subtract(target);
                BigDecimal prog = baseline.subtract(current);
                return clampLow(prog.divide(denom, 6, RoundingMode.HALF_EVEN));
            }
            if (current.signum() == 0) return BigDecimal.ZERO;
            return clampLow(target.divide(current, 6, RoundingMode.HALF_EVEN));
        }
        if (target.signum() == 0) return BigDecimal.ZERO;
        return clampLow(current.divide(target, 6, RoundingMode.HALF_EVEN));
    }

    static BigDecimal timePct(LocalDate created, LocalDate deadline, LocalDate today) {
        if (created == null || deadline == null) return null;
        long total = ChronoUnit.DAYS.between(created, deadline);
        if (total <= 0) return BigDecimal.ONE;
        long elapsed = ChronoUnit.DAYS.between(created, today);
        double t = Math.max(0d, Math.min(1d, (double) elapsed / total));
        return BigDecimal.valueOf(t);
    }

    private static BigDecimal clampLow(BigDecimal v) { return v.signum() < 0 ? BigDecimal.ZERO : v; }
    private static BigDecimal scale(BigDecimal v) { return v == null ? null : v.setScale(4, RoundingMode.HALF_EVEN); }
}
