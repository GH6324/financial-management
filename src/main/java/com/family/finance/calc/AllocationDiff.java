package com.family.finance.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.4 FR-62a · 资产配置 4 类目 diff 纯函数。
 *
 * <p>把账户余额映射到 4 类目(现金 / 投资 / 房产 / 保险),与模板 % 对比。</p>
 *
 * <p>映射规则(优先用 product_category.liquidity_class · 次用 AccountType):</p>
 * <ul>
 *   <li>liquidity_class=LIQUID → 现金(CASH 类 + 货币基金)</li>
 *   <li>liquidity_class=ILLIQUID → 房产</li>
 *   <li>liquidity_class=SEMI_LIQUID → 投资(股 / 债 / 商品)</li>
 *   <li>liquidity_class=NA → 视 AccountType:LOAN 抵消现金 / OTHER 入投资</li>
 *   <li>(保险 v0.4 占位 = 0 · v0.5 引入 INSURANCE 类型时承接)</li>
 * </ul>
 */
public final class AllocationDiff {
    private AllocationDiff() {}

    public enum Bucket { CASH, INVEST, PROPERTY, INSURANCE }

    /**
     * 从账户输入算出当前 4 类目占比(总资产分母 · 不含负债)。
     *
     * @param entries 每个有效账户一条 · balanceBase 应是绝对值正数(LOAN 由调用方传 -balance 形式或单独减)
     * @return 4 桶占比 %(和 ≤ 100 · 缺失桶为 0)
     */
    public static Map<Bucket, BigDecimal> computeCurrentPct(List<AllocationEntry> entries) {
        Map<Bucket, BigDecimal> amounts = new HashMap<>();
        for (Bucket b : Bucket.values()) amounts.put(b, BigDecimal.ZERO);

        BigDecimal totalAsset = BigDecimal.ZERO;
        for (AllocationEntry e : entries) {
            if (e.balanceBase() == null || e.balanceBase().signum() <= 0) continue;
            Bucket b = pickBucket(e);
            if (b == null) continue; // LOAN 跳过(负债不计入资产分母)
            amounts.merge(b, e.balanceBase(), BigDecimal::add);
            totalAsset = totalAsset.add(e.balanceBase());
        }
        if (totalAsset.signum() == 0) {
            return amounts.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, x -> BigDecimal.ZERO));
        }
        Map<Bucket, BigDecimal> pct = new HashMap<>();
        for (Map.Entry<Bucket, BigDecimal> e : amounts.entrySet()) {
            pct.put(e.getKey(), e.getValue()
                .multiply(new BigDecimal("100"))
                .divide(totalAsset, 2, RoundingMode.HALF_EVEN));
        }
        return pct;
    }

    /**
     * 算 diff = current% - target%(正 = 超配,负 = 欠配)。
     */
    public static Map<Bucket, BigDecimal> diff(Map<Bucket, BigDecimal> current, Map<Bucket, BigDecimal> target) {
        Map<Bucket, BigDecimal> out = new HashMap<>();
        for (Bucket b : Bucket.values()) {
            BigDecimal c = current.getOrDefault(b, BigDecimal.ZERO);
            BigDecimal t = target.getOrDefault(b, BigDecimal.ZERO);
            out.put(b, c.subtract(t).setScale(2, RoundingMode.HALF_EVEN));
        }
        return out;
    }

    /** 按映射规则把账户分到 4 桶 · LOAN 返 null(不计入资产分母) */
    private static Bucket pickBucket(AllocationEntry e) {
        String type = e.accountType();
        if ("LOAN".equals(type)) return null;

        String liq = e.liquidityClass();
        if (liq != null) {
            return switch (liq) {
                case "LIQUID" -> Bucket.CASH;
                case "ILLIQUID" -> Bucket.PROPERTY;
                case "SEMI_LIQUID" -> Bucket.INVEST;
                default -> typeFallback(type);
            };
        }
        return typeFallback(type);
    }

    private static Bucket typeFallback(String type) {
        return switch (type) {
            case "CASH" -> Bucket.CASH;
            case "STOCK", "WEALTH" -> Bucket.INVEST;
            case "PROPERTY" -> Bucket.PROPERTY;
            // INSURANCE v0.5 引入 · v0.4 没账户能命中 INSURANCE
            default -> Bucket.INVEST; // OTHER 兜底入投资
        };
    }

    /**
     * 账户输入数据(给纯函数用 · 不依赖任何 Spring/MyBatis 实体)。
     */
    public record AllocationEntry(
        BigDecimal balanceBase,
        String accountType,
        String liquidityClass
    ) {}
}
