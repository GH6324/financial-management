package com.family.finance.service.insight;

import com.family.finance.calc.BalanceSheetHealth;
import com.family.finance.calc.BehaviorHeuristics;
import com.family.finance.calc.ConcentrationCalculator;
import com.family.finance.calc.RebalanceDrift;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.family.Family;
import com.family.finance.factview.AccountPerformance;
import com.family.finance.factview.AllocationSlice;
import com.family.finance.factview.DecompositionPoint;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.factview.KpiSnapshot;
import com.family.finance.factview.TrendPoint;
import com.family.finance.repository.AccountMapper;
import com.family.finance.service.FamilyService;
import com.family.finance.service.allocation.AllocationService;
import com.family.finance.service.config.FamilyConfigService;
import com.family.finance.service.macro.WaterLevelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 资产洞察编排 · v0.6 FR-100~107。
 *
 * <p>从 FactView / 账户负债字段 / 配置 diff / 财富水位 取数,调用 calc/ 4 个纯函数,
 * 组装成可审计的「硬数据」{@link AssetInsight},供 LLM 层解读(LLM 不算数 · 见 [[feedback_llm_no_math]])。</p>
 *
 * <p><b>backward-compat</b>:全部只读取数,不写任何表;任一数据缺失局部降级(字段 null),
 * 不影响既有体检/报告链路(生产历史程序在跑)。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AssetInsightService {

    private static final long FAMILY_ID = 1L;          // 单家庭模式 · 与既有服务一致
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal REBALANCE_THRESHOLD_PP = new BigDecimal("10");
    private static final int BEHAVIOR_MIN_PERIODS = 6;

    private final FactViewService factViewService;
    private final AccountMapper accountMapper;
    private final AllocationService allocationService;
    private final FamilyService familyService;
    private final FamilyConfigService configService;
    private final WaterLevelService waterLevelService;

    /**
     * 计算全家资产洞察硬数据(只读 · 永不抛 · 失败返回 unavailable)。
     *
     * <p>不带切片的入口:自己按<b>本位币 / 全账户 / 今天往前 12 期</b>取数。
     * 给 {@code /checkup} 的 AI 诊断用 —— 那一页本来就没有币种/账户/as-of 切换。</p>
     */
    public AssetInsight compute(long familyId) {
        return compute(familyId, null);
    }

    /**
     * v1.18.7 · 允许调用方<b>把自己那张切片交进来</b>。
     *
     * <h3>为什么加这个重载</h3>
     * <p>仪表盘上的「AI · 资产洞察」条此前无条件走 {@code loadDefault(familyId)} ——
     * 本位币 + 全账户 + <b>按今天</b>往前 12 期,<b>完全无视</b>用户在这一页上选的
     * 视图币种 / 账户筛选 / 观察账期:</p>
     * <ul>
     *   <li>切到 USD → 上面 KPI 换了币种,洞察条还是本位币口径的结论</li>
     *   <li>筛掉一半账户 → KPI 只算选中的,洞察条仍按全账户算集中度</li>
     *   <li>选历史 as-of → KPI 回到那个月,洞察条仍是「今天」</li>
     * </ul>
     * <p>同一屏两个口径,而页面上看不出来。这与 v1.18.3 那次「上面的卡是本月、
     * 下面的瀑布是上月」是同一类问题 —— 只是这次差的不是期,是<b>整个取数范围</b>。</p>
     *
     * <p>能安全跟随视图,是因为这条 strip 只输出<b>百分比与档位</b>
     * (集中度 % / 负债健康档 / 净资产名义增长 % / 行为提醒条数),<b>不显示绝对金额</b> ——
     * 所以换币种不会出现「USD 数字配 ¥ 符号」那类错配。真要加金额进来,得先把币种符号一起带上。</p>
     *
     * <p>「只看外币敞口」那一处判据仍用<b>家庭本位币</b>比对账户币种 —— 那是资产本身的属性,
     * 不该随「我现在想用哪种货币看」而变。</p>
     *
     * @param slice 调用方的切片;传 null = 自己 loadDefault(老行为)
     */
    public AssetInsight compute(long familyId, FactSlice given) {
        try {
            FactSlice slice = given != null ? given : factViewService.loadDefault(familyId);
            KpiSnapshot kpi = factViewService.kpis(slice);
            if (kpi == null || kpi.totalAssets() == null || kpi.totalAssets().signum() <= 0) {
                return AssetInsight.unavailable("尚无资产快照,暂无法生成洞察");
            }
            List<AccountPerformance> perf = factViewService.accountPerformance(slice);
            List<Account> accounts = accountMapper.findActiveByFamily(familyId);
            Family family = familyService.require(familyId);
            String baseCurrency = family.getBaseCurrency() == null ? "CNY" : family.getBaseCurrency();

            double concThresholdRatio = configService.getDouble(
                    familyId, FamilyConfigService.K_CHECKUP_CONCENTRATION, 0.40);
            BigDecimal concThresholdPct = BigDecimal.valueOf(concThresholdRatio)
                    .multiply(HUNDRED).setScale(1, RoundingMode.HALF_UP);

            AssetInsight.Concentration concentration =
                    buildConcentration(kpi, perf, baseCurrency, concThresholdPct);

            // —— 资产负债表 + 加权负债利率 + 提前还贷信号 ——
            BigDecimal financialSum = sumByTypes(perf, AccountType.CASH, AccountType.STOCK,
                    AccountType.WEALTH, AccountType.CRYPTO, AccountType.INSURANCE, AccountType.OTHER);
            BigDecimal propertySum = sumByTypes(perf, AccountType.PROPERTY);
            BigDecimal weightedLoanRate = weightedLoanRate(perf, accounts);
            BigDecimal assetAnnualReturn = kpi.annualizedInvestReturnPct();
            BalanceSheetHealth.Result balanceSheet = BalanceSheetHealth.evaluate(
                    financialSum, propertySum, kpi.totalLiabilities(), kpi.totalAssets(),
                    weightedLoanRate, assetAnnualReturn);

            // —— 再平衡偏离 ——
            AllocationService.DiffResult diff = allocationService.compute(familyId, slice);
            List<RebalanceDrift.Drift> drifts = RebalanceDrift.evaluate(
                    diff.targetPct(), diff.currentPct(), REBALANCE_THRESHOLD_PP);
            AssetInsight.Rebalance rebalance =
                    new AssetInsight.Rebalance(diff.anchorCode(), REBALANCE_THRESHOLD_PP, drifts);

            // —— 行为体检 ——
            List<TrendPoint> trend = factViewService.netWorthTrend(slice);
            List<DecompositionPoint> decomp = factViewService.principalVsReturnDecomposition(slice);
            List<BehaviorHeuristics.Point> behaviorSeries = buildBehaviorSeries(trend, decomp);
            List<BigDecimal> concSeries = buildConcentrationSeries(slice);
            List<BehaviorHeuristics.Signal> behaviorSignals =
                    BehaviorHeuristics.detect(behaviorSeries, concSeries, BEHAVIOR_MIN_PERIODS);

            // —— 低利率·资产荒视角 ——
            WaterLevelService.WaterLevel wl = waterLevelService.compute(trend);
            BigDecimal cashPct = diff.currentPct() == null ? null : diff.currentPct().get("CASH");
            AssetInsight.LowRate lowRate = new AssetInsight.LowRate(
                    cashPct,
                    wl != null && wl.available() ? wl.realReturnPct() : null,
                    wl != null && wl.available() ? wl.relativeReturnPct() : null,
                    wl != null && wl.available() ? wl.nominalGrowthPct() : null);

            int historyPeriods = trend == null ? 0 : trend.size();

            return new AssetInsight(concentration, balanceSheet, weightedLoanRate, assetAnnualReturn,
                    rebalance, behaviorSignals, lowRate, historyPeriods, true, null);
        } catch (Exception e) {
            log.warn("资产洞察硬数据组装失败 familyId={}: {}", familyId, e.toString());
            return AssetInsight.unavailable("内部错误: " + e.getMessage());
        }
    }

    public AssetInsight compute() {
        return compute(FAMILY_ID);
    }

    // ---------------- 集中度 ----------------
    private AssetInsight.Concentration buildConcentration(KpiSnapshot kpi,
                                                          List<AccountPerformance> perf,
                                                          String baseCurrency,
                                                          BigDecimal thresholdPct) {
        BigDecimal total = kpi.totalAssets();
        BigDecimal propertySum = sumByTypes(perf, AccountType.PROPERTY);
        ConcentrationCalculator.Line property =
                ConcentrationCalculator.line(propertySum, total, thresholdPct);

        // 单一账户:资产端(非 LOAN)最大单账户
        String topAccLabel = null;
        BigDecimal topAccVal = null;
        for (AccountPerformance p : perf) {
            if (p.accountType() == AccountType.LOAN) continue;
            BigDecimal v = p.currentValue() == null ? BigDecimal.ZERO : p.currentValue().abs();
            if (topAccVal == null || v.compareTo(topAccVal) > 0) {
                topAccVal = v;
                topAccLabel = p.accountName();
            }
        }
        ConcentrationCalculator.Line topAccount =
                ConcentrationCalculator.line(topAccVal, total, thresholdPct);

        // 单一币种敞口:最大「非本位币」资产桶(无外币 → 降级 null)
        Map<String, BigDecimal> byCurrency = new LinkedHashMap<>();
        for (AccountPerformance p : perf) {
            if (p.accountType() == AccountType.LOAN) continue;
            String cur = p.accountCurrency() == null ? baseCurrency : p.accountCurrency();
            if (cur.equalsIgnoreCase(baseCurrency)) continue;   // 只看外币敞口
            BigDecimal v = p.currentValue() == null ? BigDecimal.ZERO : p.currentValue().abs();
            byCurrency.merge(cur, v, BigDecimal::add);
        }
        String topCurLabel = null;
        BigDecimal topCurVal = null;
        for (Map.Entry<String, BigDecimal> e : byCurrency.entrySet()) {
            if (topCurVal == null || e.getValue().compareTo(topCurVal) > 0) {
                topCurVal = e.getValue();
                topCurLabel = e.getKey();
            }
        }
        ConcentrationCalculator.Line topCurrency = topCurVal == null
                ? new ConcentrationCalculator.Line(null, thresholdPct, false)
                : ConcentrationCalculator.line(topCurVal, total, thresholdPct);

        return new AssetInsight.Concentration(total, property, topAccLabel, topAccount,
                topCurLabel, topCurrency, thresholdPct);
    }

    private static BigDecimal sumByTypes(List<AccountPerformance> perf, AccountType... types) {
        java.util.Set<AccountType> set = java.util.EnumSet.noneOf(AccountType.class);
        for (AccountType t : types) set.add(t);
        BigDecimal sum = BigDecimal.ZERO;
        for (AccountPerformance p : perf) {
            if (p.accountType() != null && set.contains(p.accountType()) && p.currentValue() != null) {
                sum = sum.add(p.currentValue().abs());
            }
        }
        return sum;
    }

    // ---------------- 加权负债利率 ----------------
    private BigDecimal weightedLoanRate(List<AccountPerformance> perf, List<Account> accounts) {
        Map<Long, BigDecimal> rateByAccount = new java.util.HashMap<>();
        for (Account a : accounts) {
            if (a.getType().isLiability() && a.getAnnualRatePct() != null) {
                rateByAccount.put(a.getId(), a.getAnnualRatePct());
            }
        }
        if (rateByAccount.isEmpty()) return null;
        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal balanceSum = BigDecimal.ZERO;
        for (AccountPerformance p : perf) {
            if (p.accountType() != AccountType.LOAN) continue;
            BigDecimal rate = rateByAccount.get(p.accountId());
            if (rate == null) continue;
            BigDecimal bal = p.currentValue() == null ? BigDecimal.ZERO : p.currentValue().abs();
            weightedSum = weightedSum.add(rate.multiply(bal));
            balanceSum = balanceSum.add(bal);
        }
        if (balanceSum.signum() <= 0) return null;
        return weightedSum.divide(balanceSum, 3, RoundingMode.HALF_UP);
    }

    // ---------------- 行为序列 ----------------
    /** 把净资产趋势 + 累计净流入分解拼成逐期 (净资产, 当期净流入) 序列。 */
    private List<BehaviorHeuristics.Point> buildBehaviorSeries(List<TrendPoint> trend,
                                                               List<DecompositionPoint> decomp) {
        List<BehaviorHeuristics.Point> out = new ArrayList<>();
        if (trend == null || trend.isEmpty()) return out;
        // 当期净流入 = 累计净流入逐期差分(decomp 与 trend 同期序)
        List<BigDecimal> netInflows = new ArrayList<>();
        BigDecimal prevCum = null;
        if (decomp != null) {
            for (DecompositionPoint d : decomp) {
                BigDecimal cum = d.cumulativeNetInflow() == null ? BigDecimal.ZERO : d.cumulativeNetInflow();
                netInflows.add(prevCum == null ? cum : cum.subtract(prevCum));
                prevCum = cum;
            }
        }
        for (int i = 0; i < trend.size(); i++) {
            BigDecimal nw = trend.get(i).value();
            BigDecimal inflow = i < netInflows.size() ? netInflows.get(i) : BigDecimal.ZERO;
            out.add(new BehaviorHeuristics.Point(nw, inflow));
        }
        return out;
    }

    /** 逐期「最大资产类目占比 %」序列 —— 喂 CONCENTRATION_RISING 信号。 */
    private List<BigDecimal> buildConcentrationSeries(FactSlice slice) {
        List<BigDecimal> out = new ArrayList<>();
        if (slice == null || slice.periodIds() == null) return out;
        for (Long periodId : slice.periodIds()) {
            List<AllocationSlice> alloc = factViewService.allocationByType(slice, periodId);
            BigDecimal maxRatio = null;
            for (AllocationSlice a : alloc) {
                if (a.ratio() == null) continue;
                if (maxRatio == null || a.ratio().compareTo(maxRatio) > 0) maxRatio = a.ratio();
            }
            if (maxRatio != null) {
                out.add(maxRatio.multiply(HUNDRED).setScale(1, RoundingMode.HALF_UP));
            }
        }
        return out;
    }
}
