package com.family.finance.service.goal;

import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.goal.GoalMetric;
import com.family.finance.factview.AccountPerformance;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.factview.KpiSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

/**
 * v0.16 · 目标追踪指标求值:把「一组账户(0..N)的某指标」算成一个当前值。
 *
 * <p>口径(TDD 决策 B/C):金额类 Σ;比率类按当前价值加权(近似,非严格组合 TWR);
 * 0 账户 = 全家(取 KPI / familyTwr / savingsRate)。比率类返回<b>百分数</b>(12.3 = 12.3%),
 * 金额类返回本位币金额。空安全:缺值当 0(比率加权时跳过缺值项)。</p>
 */
@Component
@RequiredArgsConstructor
public class GoalMetricEvaluator {

    private final FactViewService factView;

    /** 取当前值(实时算不落库)· accountIds 空/ null = 全家。 */
    public BigDecimal current(long familyId, GoalMetric metric, Set<Long> accountIds) {
        FactSlice slice = factView.loadDefault(familyId);
        List<AccountPerformance> perf = factView.accountPerformance(slice);
        KpiSnapshot kpi = factView.kpis(slice);
        BigDecimal savings = pct(factView.savingsRate(slice));
        BigDecimal familyTwr = pct(factView.familyTwr(slice));
        return aggregate(metric, accountIds, perf, kpi, savings, familyTwr);
    }

    /**
     * 纯聚合(单测入口 · 无 IO)。比率入参 savingsRatePct/familyTwrPct 已是百分数口径。
     */
    public static BigDecimal aggregate(GoalMetric metric, Set<Long> accountIds,
                                       List<AccountPerformance> perf, KpiSnapshot kpi,
                                       BigDecimal savingsRatePct, BigDecimal familyTwrPct) {
        boolean whole = accountIds == null || accountIds.isEmpty();
        List<AccountPerformance> sel = whole ? perf
                : perf.stream().filter(a -> accountIds.contains(a.accountId())).toList();
        return switch (metric) {
            case AMOUNT_TOTAL -> whole ? nz(kpi.netWorth()) : sum(sel, AccountPerformance::currentValue);
            case CASH_TOTAL -> whole ? nz(kpi.liquidAssets())
                    : sum(cashOnly(sel), AccountPerformance::currentValue);
            case NET_PRINCIPAL -> sum(sel, AccountPerformance::netPrincipal);
            case CUM_PNL -> sum(sel, AccountPerformance::cumPnl);
            case PERIOD_PNL -> sum(sel, AccountPerformance::latestPnl);
            case TOTAL_ASSETS -> whole ? nz(kpi.totalAssets()) : sumPositive(sel);
            case TOTAL_LIAB -> whole ? nz(kpi.totalLiabilities()) : sumLiabilities(sel);
            case RETURN_XIRR -> whole ? nz(familyTwrPct) : pct(weighted(sel, AccountPerformance::xirr));
            case RETURN_BASE -> whole ? nz(familyTwrPct) : pct(weighted(sel, AccountPerformance::returnBase));
            case SHARE_PCT -> whole ? new BigDecimal("100")
                    : ratioPct(sum(sel, AccountPerformance::currentValue), kpi.netWorth());
            case MOM_PCT -> whole ? pct(kpi.netWorthDeltaPct()) : pct(weighted(sel, AccountPerformance::momPct));
            case SAVINGS_RATE -> nz(savingsRatePct);
            case MAX_DRAWDOWN -> pct(weighted(sel, AccountPerformance::maxDrawdownPct));
            case EMERGENCY_MONTHS -> nz(kpi.emergencyFundMonths());
        };
    }

    // ---------- 聚合原语 ----------

    private static List<AccountPerformance> cashOnly(List<AccountPerformance> l) {
        return l.stream().filter(a -> a.accountType() == AccountType.CASH).toList();
    }

    private interface Getter { BigDecimal get(AccountPerformance a); }

    private static BigDecimal sum(List<AccountPerformance> l, Getter g) {
        BigDecimal s = BigDecimal.ZERO;
        for (AccountPerformance a : l) s = s.add(nz(g.get(a)));
        return s;
    }

    /** 只加正值(总资产:剔除贷款等负值)。 */
    private static BigDecimal sumPositive(List<AccountPerformance> l) {
        BigDecimal s = BigDecimal.ZERO;
        for (AccountPerformance a : l) { BigDecimal v = nz(a.currentValue()); if (v.signum() > 0) s = s.add(v); }
        return s;
    }

    /** 负债:取负值账户绝对值之和(贷款类)。 */
    private static BigDecimal sumLiabilities(List<AccountPerformance> l) {
        BigDecimal s = BigDecimal.ZERO;
        for (AccountPerformance a : l) { BigDecimal v = nz(a.currentValue()); if (v.signum() < 0) s = s.add(v.abs()); }
        return s;
    }

    /** 价值加权平均:Σ(v_i·w_i)/Σw_i,w_i=|currentValue|;缺值项跳过。 */
    private static BigDecimal weighted(List<AccountPerformance> l, Getter g) {
        BigDecimal num = BigDecimal.ZERO, den = BigDecimal.ZERO;
        for (AccountPerformance a : l) {
            BigDecimal v = g.get(a);
            if (v == null) continue;
            BigDecimal w = nz(a.currentValue()).abs();
            num = num.add(v.multiply(w));
            den = den.add(w);
        }
        return den.signum() == 0 ? BigDecimal.ZERO : num.divide(den, 6, RoundingMode.HALF_EVEN);
    }

    // ---------- 单位换算 ----------

    /** 比率(0.123)→ 百分数(12.3);null→0。 */
    private static BigDecimal pct(BigDecimal ratio) {
        return ratio == null ? BigDecimal.ZERO : ratio.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal ratioPct(BigDecimal part, BigDecimal whole) {
        if (part == null || whole == null || whole.signum() == 0) return BigDecimal.ZERO;
        return part.multiply(new BigDecimal("100")).divide(whole, 2, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
