package com.family.finance.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * v0.4 FR-61b/c · 基准对照计算 · 纯函数。
 *
 * <p>给账户 XIRR 加上"vs 基准"对照:</p>
 * <ul>
 *   <li>账户级:account.xirr - product_category.benchmark_pct → diff</li>
 *   <li>家庭级:按账户余额加权平均 product_category.benchmark_pct → familyBenchmark</li>
 * </ul>
 *
 * <p>fallback:account 没 product_category_code 的,按 AccountType 兜底
 * (STOCK→6% / WEALTH→3% / CASH→0.5% / PROPERTY→3% / LOAN→0% / OTHER→0%)</p>
 */
public final class BenchmarkAggregator {
    private BenchmarkAggregator() {}

    /** AccountType → 兜底年化基准 %(account 没设产品类目时) */
    private static final Map<String, BigDecimal> TYPE_FALLBACK = Map.of(
        "CASH",     new BigDecimal("0.50"),
        "STOCK",    new BigDecimal("6.00"),
        "WEALTH",   new BigDecimal("3.00"),
        "PROPERTY", new BigDecimal("3.00"),
        "LOAN",     BigDecimal.ZERO,
        "OTHER",    BigDecimal.ZERO
    );

    /** 跑赢/输判定阈值(±2%) */
    public static final BigDecimal BEAT_THRESHOLD = new BigDecimal("2.0");

    public enum BeatStatus { BEAT, FLAT, MISS, NA }

    /**
     * 单账户 diff · 用 product_category.benchmark_pct 或类型兜底。
     *
     * @param accountXirr 小数形式(0.072 = 7.2%) · null 时返回 NA
     * @param pcBenchmarkPct 产品类目年化基准 %(8.00 = 8%) · 可空
     * @param accountTypeName CASH/STOCK/WEALTH/PROPERTY/LOAN/OTHER
     * @return 基准 %(年化形式 · 6.00 = 6%)· 永不返回 null
     */
    public static BigDecimal benchmarkForAccount(BigDecimal accountXirr, BigDecimal pcBenchmarkPct, String accountTypeName) {
        if (pcBenchmarkPct != null) return pcBenchmarkPct.setScale(2, RoundingMode.HALF_EVEN);
        return TYPE_FALLBACK.getOrDefault(accountTypeName, BigDecimal.ZERO);
    }

    /** 账户级 diff = xirr% - benchmark% · 都是百分点形式 */
    public static BigDecimal diffPercentPoints(BigDecimal accountXirr, BigDecimal benchmarkPct) {
        if (accountXirr == null) return null;
        BigDecimal xirrPct = accountXirr.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_EVEN);
        return xirrPct.subtract(benchmarkPct).setScale(2, RoundingMode.HALF_EVEN);
    }

    /** 跑赢/输判定 · diff > +2 = BEAT / diff < -2 = MISS / 之间 = FLAT */
    public static BeatStatus beatStatus(BigDecimal diffPct) {
        if (diffPct == null) return BeatStatus.NA;
        if (diffPct.compareTo(BEAT_THRESHOLD) > 0) return BeatStatus.BEAT;
        if (diffPct.compareTo(BEAT_THRESHOLD.negate()) < 0) return BeatStatus.MISS;
        return BeatStatus.FLAT;
    }

    /**
     * 家庭加权基准 = Σ(account.balance × benchmark) / Σ(account.balance)
     *
     * @param entries 每个账户一条 (balance, benchmark_pct) · balance 应已折算到本位币
     * @return 加权年化 %(永不 null · 空列表返 0)
     */
    public static BigDecimal weightedFamilyBenchmark(List<BenchmarkInput> entries) {
        if (entries == null || entries.isEmpty()) return BigDecimal.ZERO;
        BigDecimal numerator = BigDecimal.ZERO;
        BigDecimal denominator = BigDecimal.ZERO;
        for (BenchmarkInput e : entries) {
            if (e.balanceBase() == null || e.balanceBase().signum() <= 0) continue;
            if (e.benchmarkPct() == null) continue;
            numerator = numerator.add(e.balanceBase().multiply(e.benchmarkPct()));
            denominator = denominator.add(e.balanceBase());
        }
        if (denominator.signum() == 0) return BigDecimal.ZERO;
        return numerator.divide(denominator, 2, RoundingMode.HALF_EVEN);
    }

    public record BenchmarkInput(BigDecimal balanceBase, BigDecimal benchmarkPct) {}
}
