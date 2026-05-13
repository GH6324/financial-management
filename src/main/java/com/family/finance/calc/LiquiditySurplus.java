package com.family.finance.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * v0.4 FR-62c · 应急金"不闲置"判定 · 纯函数。
 *
 * <p>规则:LIQUID 类账户余额合计 > 应急储备需求 × 1.5x 时,触发"超额闲置"提示。</p>
 *
 * <p>应急储备需求 = 月均支出 × 默认 6 个月(可由 FR-50c emergency goal 设置)。</p>
 *
 * <p>1.5x 阈值理由:留 50% buffer 避免月支出小幅波动就触发。</p>
 */
public final class LiquiditySurplus {
    private LiquiditySurplus() {}

    /** 默认应急倍数 */
    public static final int DEFAULT_EMERGENCY_MONTHS = 6;

    /** 触发"闲置"的倍率阈值 */
    public static final BigDecimal SURPLUS_MULTIPLIER = new BigDecimal("1.5");

    /**
     * 计算 surplus 评估。
     *
     * @param liquidAssets 当前 LIQUID 类账户余额合计(本位币 · 含货币基金 v0.3.3)
     * @param avgMonthlyExpense 月均支出(本位币)
     * @param emergencyMonths 应急储备目标月数(可空 · 用 6 兜底)
     * @return Result · 永不返 null
     */
    public static Result evaluate(BigDecimal liquidAssets, BigDecimal avgMonthlyExpense, Integer emergencyMonths) {
        if (liquidAssets == null) liquidAssets = BigDecimal.ZERO;
        if (avgMonthlyExpense == null || avgMonthlyExpense.signum() <= 0) {
            return new Result(false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }
        int months = emergencyMonths != null && emergencyMonths > 0 ? emergencyMonths : DEFAULT_EMERGENCY_MONTHS;
        BigDecimal needed = avgMonthlyExpense.multiply(BigDecimal.valueOf(months)).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal threshold = needed.multiply(SURPLUS_MULTIPLIER).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal surplus = liquidAssets.subtract(needed).setScale(2, RoundingMode.HALF_EVEN);
        boolean shouldNotify = liquidAssets.compareTo(threshold) > 0;
        return new Result(shouldNotify, liquidAssets, needed, surplus.max(BigDecimal.ZERO), months);
    }

    /**
     * @param shouldNotify 是否触发"不闲置"banner
     * @param liquidAssets 当前 LIQUID 总额
     * @param emergencyNeeded 应急储备需求
     * @param surplus 超额(永 ≥ 0,即使 shouldNotify=false 也可能是 0)
     * @param emergencyMonths 应急倍数(默认 6)
     */
    public record Result(
        boolean shouldNotify,
        BigDecimal liquidAssets,
        BigDecimal emergencyNeeded,
        BigDecimal surplus,
        int emergencyMonths
    ) {}
}
