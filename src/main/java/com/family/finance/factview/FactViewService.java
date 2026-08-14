package com.family.finance.factview;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface FactViewService {
    FactSlice loadDefault(Long familyId);

    FactSlice load(FactFilter filter);

    KpiSnapshot kpis(FactSlice slice);

    List<TrendPoint> netWorthTrend(FactSlice slice);

    /** v0.13 · 剔除累计开账基线的净资产趋势(财富水位用) */
    List<TrendPoint> netWorthTrendExOpening(FactSlice slice);

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

    /**
     * v1.10 · **逐期**资金流分解(ΔNW = 人赚 + 开账基线 + 钱赚)。
     * 只走已关账期,与 familyXirr / familyTwr / 本月资产收益 同锚。
     * {@link #principalVsReturnDecomposition} 的累计值由它累加而来 —— 逐期口径只有这一份实现。
     */
    List<PeriodFlow> periodFlows(FactSlice slice);

    /**
     * v1.10 · 某一期的存量余额切面(净资产 / 总资产 / 总负债 / 流动资产)。
     * {@code kpis()} 内部也走它 —— ASSET/LIABILITY/LIQUID 三个谓词只有一份实现。
     */
    PeriodBalance balanceAt(FactSlice slice, Long periodId);


    List<DecompositionPoint> principalVsReturnDecomposition(FactSlice slice);

    List<TrendPoint> debtTrend(FactSlice slice);

    List<AccountPerformance> accountPerformance(FactSlice slice);

    /**
     * v1.12 FR-350 · 同上,但 {@code sealedAttrs=true} 时「预期年化 %」取<b>锚期定格值</b>
     * (锚期 = 窗口内最新已关账期,与账户 xirr 同源),而不是当前的 account / product_category 行。
     *
     * <p>为什么要这个开关,而不是一律定格:<b>报表页</b>是封板视图,承诺「已关账的月份不再变」,
     * 所以「预实」这一列必须可复现 —— 今天改一个账户的预期年化,去年 12 月的预实不该跟着重算。
     * <b>仪表盘</b>是实时视图,用户刚把某账户的预期年化从 6% 改成 8%,仪表盘就该立刻按 8% 算;
     * 若也定格到最新已关账期,会变成「改了没反应,要等下次关账」—— 那是 bug 不是封板。
     *
     * <p>所以两个页面在这一列上<b>刻意不同</b>:reports 传 {@code true},dashboard / AI 洞察 /
     * lens / 目标评估走无参重载(实时)。护栏 {@code v112-ATTR-BENCH-ANCHOR} 钉住这个分工。
     */
    List<AccountPerformance> accountPerformance(FactSlice slice, boolean sealedAttrs);

    /**
     * 家庭净资产 环比(MoM)/ 同比(YoY)· v0.8。
     * filter 应覆盖 [as-of − 12 期, as-of](与 dashboard 显示窗口解耦),实时算不落库;
     * 对比账期缺失则对应字段为 null。
     */
    MomYoy momYoy(FactFilter filter);

    /** v1.2 F · 复用已加载 slice(窗口须覆盖 asof−12 月)· 消灭 dashboard 第二次 load */
    MomYoy momYoy(FactSlice slice);
}
