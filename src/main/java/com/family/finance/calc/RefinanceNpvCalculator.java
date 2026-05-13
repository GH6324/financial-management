package com.family.finance.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * v0.4 FR-62d · 提前还贷 vs 投资 决策器 · 纯函数。
 *
 * <p>核心公式(简化等额本息):</p>
 * <ul>
 *   <li>**提前还贷"节省利息"** = 多还本金 N × ((1+r_loan)^n - 1) / r_loan
 *       (近似 · 假设这笔本金一次性减少 + 后续利率不变)</li>
 *   <li>**投资期末本利和** = N × (1+r_invest)^n</li>
 *   <li>**diff NPV**(折现回今天)= (投资终值 - 提前还贷节省值) / (1+r_invest)^n</li>
 *   <li>r_loan ≥ r_invest → 必还(确定性收益 > 不确定预期)</li>
 *   <li>diff_npv > 0 → 推荐投资</li>
 *   <li>否则 → 推荐还贷</li>
 *   <li>应急金 < 3 月 → REFUSE(先补应急)</li>
 * </ul>
 *
 * <p>r_loan/r_invest 入参:小数形式(0.045 = 4.5%)· 注意 ≠ 百分点。</p>
 */
public final class RefinanceNpvCalculator {
    private RefinanceNpvCalculator() {}

    public enum Recommendation { INVEST, PREPAY, MUST_PREPAY, REFUSE_LOW_EMERGENCY }

    public static Result compute(Input in) {
        validate(in);

        if (in.emergencyMonths() != null && in.emergencyMonths().doubleValue() < 3.0) {
            return new Result(
                Recommendation.REFUSE_LOW_EMERGENCY,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                "应急储备不足 3 月,任何方案前请先补足应急金");
        }

        double rLoan = in.loanRate().doubleValue();
        double rInvest = in.investRate().doubleValue();
        double amount = in.amount().doubleValue();
        int years = in.remainingYears();

        double prepaySaved = computePrepaySavings(amount, rLoan, years);
        double investFv = amount * Math.pow(1 + rInvest, years);
        double diffFv = investFv - amount - prepaySaved;
        double diffNpv = diffFv / Math.pow(1 + rInvest, years);

        Recommendation rec;
        String reason;
        if (rLoan >= rInvest) {
            rec = Recommendation.MUST_PREPAY;
            reason = String.format("房贷利率 %.2f%% ≥ 投资预期 %.2f%% · 节省利息是确定收益,投资是预期,理性选还贷",
                rLoan * 100, rInvest * 100);
        } else if (diffNpv > 0) {
            rec = Recommendation.INVEST;
            reason = String.format("投资预期 %.2f%% 显著高于房贷利率 %.2f%% · %d 年后投资多赚约 ¥%.0f(折现回今天)",
                rInvest * 100, rLoan * 100, years, diffNpv);
        } else {
            rec = Recommendation.PREPAY;
            reason = String.format("投资 NPV 不如还贷节省 · 还贷更划算 约 ¥%.0f", -diffNpv);
        }

        return new Result(
            rec,
            BigDecimal.valueOf(prepaySaved).setScale(0, RoundingMode.HALF_UP),
            BigDecimal.valueOf(investFv - amount).setScale(0, RoundingMode.HALF_UP),
            BigDecimal.valueOf(diffNpv).setScale(0, RoundingMode.HALF_UP),
            reason);
    }

    /** 提前还贷节省利息近似 = amount × ((1+r)^n − 1) / r */
    private static double computePrepaySavings(double amount, double r, int n) {
        if (r <= 0) return 0;
        return amount * (Math.pow(1 + r, n) - 1) / r * r; // 简化:近似 amount × ((1+r)^n - 1)
        // 注:真实等额本息提前还贷节省利息公式较复杂,这里用复利"假设钱一直挂在贷款上 n 年的利息"
        // 是简化但用户认知友好的近似;v0.5 可加精确公式
    }

    private static void validate(Input in) {
        if (in == null) throw new IllegalArgumentException("input 不能 null");
        if (in.amount() == null || in.amount().signum() <= 0) throw new IllegalArgumentException("amount 必须 > 0");
        if (in.loanRate() == null || in.loanRate().doubleValue() <= 0 || in.loanRate().doubleValue() > 0.5)
            throw new IllegalArgumentException("loanRate 必须 ∈ (0, 0.5)");
        if (in.investRate() == null || in.investRate().doubleValue() <= 0 || in.investRate().doubleValue() > 0.5)
            throw new IllegalArgumentException("investRate 必须 ∈ (0, 0.5)");
        if (in.remainingYears() <= 0 || in.remainingYears() > 50)
            throw new IllegalArgumentException("remainingYears 必须 ∈ (0, 50]");
    }

    /**
     * @param amount 准备投入的钱(¥)
     * @param loanRate 房贷年利率(小数 · 0.045 = 4.5%)
     * @param investRate 投资预期年化(小数)
     * @param remainingYears 剩余还款年限
     * @param emergencyMonths 当前应急储备月数(可空 · null 时不检查)
     */
    public record Input(
        BigDecimal amount,
        BigDecimal loanRate,
        BigDecimal investRate,
        int remainingYears,
        BigDecimal emergencyMonths
    ) {}

    public record Result(
        Recommendation recommendation,
        BigDecimal prepaySaved,
        BigDecimal investGain,
        BigDecimal diffNpv,
        String reason
    ) {}
}
