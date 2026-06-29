package com.family.finance.factview;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface FactViewService {
    FactSlice loadDefault(Long familyId);

    FactSlice load(FactFilter filter);

    KpiSnapshot kpis(FactSlice slice);

    List<TrendPoint> netWorthTrend(FactSlice slice);

    List<AllocationSlice> allocationByType(FactSlice slice, Long periodId);

    List<WaterfallSegment> incomeExpenseWaterfall(FactSlice slice);

    /**
     * v0.10 · 某期家庭毛收入/毛支出/净流入(人赚)· viewCurrency。
     * 与人赚(lastNetInflow)同源同分支(PMC 优先 · 空回退 cash_flow),保证 income−expense==净流入。
     */
    CashflowBreakdown cashflowBreakdown(FactSlice slice, Long periodId);

    /**
     * v0.10 · 近 n 期收支序列(view 币种 · 含进行中 OPEN 期)· 给仪表盘实时收支趋势用。
     * livePeriodId 命中的点标 live=true(进行中);传 null 则无 live 标记。
     */
    List<CashflowPoint> cashflowSeries(FactSlice slice, int n, Long livePeriodId);

    BigDecimal savingsRate(FactSlice slice);

    Map<Long, BigDecimal> accountXirr(FactSlice slice);

    BigDecimal familyXirr(FactSlice slice);

    BigDecimal familyTwr(FactSlice slice);

    List<DecompositionPoint> principalVsReturnDecomposition(FactSlice slice);

    List<TrendPoint> debtTrend(FactSlice slice);

    List<AccountPerformance> accountPerformance(FactSlice slice);

    /**
     * 家庭净资产 环比(MoM)/ 同比(YoY)· v0.8。
     * filter 应覆盖 [as-of − 12 期, as-of](与 dashboard 显示窗口解耦),实时算不落库;
     * 对比账期缺失则对应字段为 null。
     */
    MomYoy momYoy(FactFilter filter);
}
