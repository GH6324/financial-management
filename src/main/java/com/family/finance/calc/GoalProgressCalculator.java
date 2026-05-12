package com.family.finance.calc;

import com.family.finance.domain.goal.GoalParams;
import com.family.finance.domain.goal.GoalType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;

/**
 * 目标进度纯计算 · v0.3 FR-50。
 *
 * <p>纯函数 · 无外部依赖 · 输入数据由 caller 准备。包括三类目标的:</p>
 * <ul>
 *   <li>目标值(target / 通胀调整后)</li>
 *   <li>当前进度 % (PV / target)</li>
 *   <li>月度供款中位数(从 income - expense 历史推)</li>
 * </ul>
 *
 * <p>目标值公式见 §3.1 / TDD 决策 22。</p>
 */
public final class GoalProgressCalculator {

    private GoalProgressCalculator() {}

    /**
     * 计算目标值(通胀调整后)。
     *
     * @param type   目标类型
     * @param params 类型特定参数
     * @param emergencyBaseline EMERGENCY 类型的月支出基线(NULL = caller 未提供 · 自动 baseline 失败)
     * @return 目标本金需求(本位币 · 通胀调整后)· EMERGENCY 类型若基线 NULL 返回 ZERO
     */
    public static BigDecimal computeTarget(GoalType type, GoalParams params, BigDecimal emergencyBaseline) {
        return switch (type) {
            case RETIREMENT -> computeRetirementTarget(params);
            case EDUCATION  -> computeEducationTarget(params);
            case EMERGENCY  -> computeEmergencyTarget(params, emergencyBaseline);
        };
    }

    /**
     * 退休本金需求 = monthly_expense × 12 × (1 + inflation)^(retire_age - current_age) / withdrawal_rate
     */
    public static BigDecimal computeRetirementTarget(GoalParams p) {
        require(p.getMonthlyExpense(), "monthly_expense");
        require(p.getRetireAge(), "retire_age");
        require(p.getCurrentAge(), "current_age");
        BigDecimal inflation = orDefault(p.getInflationRate(), new BigDecimal("0.025"));
        BigDecimal withdrawal = orDefault(p.getWithdrawalRate(), new BigDecimal("0.04"));
        int years = Math.max(0, p.getRetireAge() - p.getCurrentAge());
        double annualSpend = p.getMonthlyExpense().doubleValue() * 12d;
        double inflatedAnnual = annualSpend * Math.pow(1d + inflation.doubleValue(), years);
        double principal = inflatedAnnual / withdrawal.doubleValue();
        return BigDecimal.valueOf(principal).setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * 教育金需求 = target_amount × (1 + inflation)^(target_year - current_year)
     */
    public static BigDecimal computeEducationTarget(GoalParams p) {
        require(p.getTargetAmount(), "target_amount");
        require(p.getChildBirthYear(), "child_birth_year");
        int offset = orDefault(p.getTargetYearOffset(), 18);
        int targetYear = p.getChildBirthYear() + offset;
        int currentYear = Year.now().getValue();
        int years = Math.max(0, targetYear - currentYear);
        BigDecimal inflation = orDefault(p.getInflationRate(), new BigDecimal("0.03"));
        double inflated = p.getTargetAmount().doubleValue() * Math.pow(1d + inflation.doubleValue(), years);
        return BigDecimal.valueOf(inflated).setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * 应急目标 = baseline × months_target · baseline 优先用 auto(caller 提供过去 3 期 total_expense 中位)
     * 若 auto_baseline=false 或 NULL 用 fixed_baseline。
     */
    public static BigDecimal computeEmergencyTarget(GoalParams p, BigDecimal autoBaseline) {
        int months = orDefault(p.getMonthsTarget(), 6);
        BigDecimal baseline;
        if (Boolean.FALSE.equals(p.getAutoBaseline()) && p.getFixedBaseline() != null) {
            baseline = p.getFixedBaseline();
        } else {
            baseline = autoBaseline;
        }
        if (baseline == null) {
            return BigDecimal.ZERO;
        }
        return baseline.multiply(BigDecimal.valueOf(months)).setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * 进度 % = PV / target · 范围 [0, ∞)· target=0 时返回 0。
     */
    public static BigDecimal computeProgress(BigDecimal pv, BigDecimal target) {
        if (pv == null || target == null || target.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return pv.divide(target, 6, RoundingMode.HALF_EVEN);
    }

    /**
     * 月度供款中位数 · 从过去 N 期 (income - expense) 推 · NULL 期(任一字段缺)已被 caller 过滤掉。
     *
     * @return 中位数(本位币 · 月)· 输入为空时返回 ZERO
     */
    public static BigDecimal medianMonthlyContribution(List<BigDecimal> savings) {
        if (savings == null || savings.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<BigDecimal> sorted = savings.stream().sorted().toList();
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        BigDecimal lo = sorted.get(n / 2 - 1);
        BigDecimal hi = sorted.get(n / 2);
        return lo.add(hi).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_EVEN);
    }

    /**
     * 退休目标 · 计算距退休的剩余月数(从 today 到目标日)· 给反推月供用。
     */
    public static int monthsToTargetDate(LocalDate targetDate) {
        if (targetDate == null) return 0;
        LocalDate today = LocalDate.now();
        int months = (targetDate.getYear() - today.getYear()) * 12
                   + (targetDate.getMonthValue() - today.getMonthValue());
        return Math.max(0, months);
    }

    // ---------- helpers ----------

    private static void require(Object v, String name) {
        if (v == null) {
            throw new IllegalArgumentException("missing required param: " + name);
        }
    }

    private static <T> T orDefault(T v, T fallback) {
        return v != null ? v : fallback;
    }
}
