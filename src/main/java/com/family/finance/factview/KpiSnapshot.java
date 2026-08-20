package com.family.finance.factview;

import java.math.BigDecimal;

/**
 * KPI 快照 · v0.4.2 加 3 个"资产年化"字段(剔除外部现金流的纯投资指标)。
 *
 * <p>"含收入" vs "剔除收入" 二分:</p>
 * <ul>
 *   <li>含收入(XIRR · 资金加权 · v0.1)在 reports / dashboard 老 KPI 里</li>
 *   <li>剔除收入(TWR / 资产年化 · 本字段)反映真实"钱替你赚钱"能力</li>
 * </ul>
 *
 * @param netWorth 净资产 = 总资产 − 总负债
 * @param totalAssets 总资产(不含负债)
 * @param totalLiabilities 总负债(绝对值)
 * @param emergencyFundMonths 紧急储备月数 = LIQUID / 月均支出
 * @param debtToAssetRatio 负债率 = 负债 / 资产
 * @param netWorthDelta 净资产 Δ(vs 上期)
 * @param netWorthDeltaPct Δ%
 * @param monthlyPnlAmount v0.4.2 · 本月 PnL 金额(剔除外部现金流 · 纯投资变动)· 可空
 * @param monthlyInvestReturnPct v0.4.2 · 本月 PnL 占期初比率(0.028 = 2.8%)· 可空
 * @param annualizedInvestReturnPct v0.4.2 · 滚动 12 月年化纯投资收益(几何平均)· = familyTwr · 可空
 * @param ytdInvestPnl v0.4.2 · 本年累计纯投资 PnL 金额(自然年 · 剔除现金流)· 可空
 * @param liquidAssets v0.5.3 · 流动资产(LIQUID 类目期末合计 · viewCurrency)· 紧急储备分子 · 可空
 * @param avgExpense v0.5.3 · 近 12 月月均支出(PMC 优先 · viewCurrency)· 紧急储备分母 · 可空
 * @param prevNetWorth v0.5.3 · 上期期末净资产(viewCurrency)· 本月资产收益% 的"期初" · 可空
 * @param lastNetInflow v0.5.3 · 本期净流入(人赚 · PMC 优先 · viewCurrency)· 可空
 */
public record KpiSnapshot(
        BigDecimal netWorth,
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        BigDecimal emergencyFundMonths,
        BigDecimal debtToAssetRatio,
        BigDecimal netWorthDelta,
        BigDecimal netWorthDeltaPct,
        // v0.4.2 "资产年化"二分系列(剔除外部收入的纯投资视角)
        BigDecimal monthlyPnlAmount,
        BigDecimal monthlyInvestReturnPct,
        BigDecimal annualizedInvestReturnPct,
        BigDecimal ytdInvestPnl,
        // v0.5.3 · 计算口径透明化:把原本算完即弃的中间量带出来,供 tooltip 展示真实数值
        BigDecimal liquidAssets,
        BigDecimal avgExpense,
        BigDecimal prevNetWorth,
        BigDecimal lastNetInflow,
        // v0.13 · 本期「开账基线」(新纳入账户存量本金 · viewCurrency)· 供「本期怎么变」卡第三项 + 收益指标剔除 · 可空
        BigDecimal openingBaselineLast,
        /**
         * v1.6.30 · 收益锚点期的期末净资产(viewCurrency)。
         *
         * <p>存量类 KPI({@code netWorth} 等)锚最后一期(可能是进行中的 OPEN 期,余额已填 → 该看最新);
         * 收益类({@code monthlyPnlAmount / monthlyInvestReturnPct})锚**最新已关账期** ——
         * 进行中的期收支通常还没录,拿它算收益会把未录收支整个算成投资收益。
         * 两者可能不是同一期,所以 tooltip 需要单独知道收益锚点的期末值。</p>
         */
        BigDecimal returnAnchorNetWorth,
        /** v1.6.30 · 收益锚点期的月份(给「锚定 2026-07」这类文案)· 可空 */
        java.time.LocalDate returnAnchorMonth,
        /** v1.6.30 · 最后一期是否「填报中」(在窗口内但未关账)· 页面据此提示口径 */
        boolean filingInProgress,
        /** v1.6.30 · 参与收益类计算的期数(= 已关账期数)· 决定 XIRR 是年化还是累计口径 · 可空 */
        Integer returnPeriodCount,

        // ── v1.10 FR-327 · 「实时本月」口径(仪表盘专用)────────────────────────────
        //   上面那组收益字段锚**最新已关账期**,给报表页的封板快照用 —— 那是对的,不动。
        //   仪表盘按两页分工要显示**当月实时**,所以并列加一组锚**当前期**(含进行中)的值。
        //   刻意用加字段而不是给 FactFilter 加策略参数:纯加法,现有字段与全部现有护栏/单测
        //   (ClosedPeriodAnchorTest)一字不动,而且口径差异在字段名上就看得见(带 live)。
        //   算法与上面那组**完全同一个** InvestmentReturnCalculator.monthly(),只换锚点。
        /** 当前期(可能进行中)的投资损益金额 · 可空 */
        BigDecimal liveMonthlyPnlAmount,
        /** 当前期(可能进行中)的资产收益率 · 可空 */
        BigDecimal liveMonthlyInvestReturnPct,
        /** 当前期已录收入 · 用来判断这个实时值有多可信(录得越少,收益越虚高) */
        BigDecimal liveIncome,
        /** 当前期已录支出 */
        BigDecimal liveExpense,

        // ── v1.18.1 · 归因复盘专用:收益锚点期的 ΔNW 与开账基线 ───────────────────────
        //   归因瀑布靠恒等式 ΔNW = 人赚 + 钱赚 + 开账基线 + 未归因 闭合,四项必须**同一期**。
        //   原实现四项都取 lastPeriodId(可能是进行中的 OPEN 期)—— 那一期典型是「余额还没填、
        //   转账已登记」,于是某账户的 pnl = Δ余额(0) − 转入 = 负的转入额,排行榜把它列成
        //   「亏得最多」。prod 2026-08 实测:萝卜-余额宝 只收到一笔转入,却被显示成亏了那笔的全额。
        //   所以归因整体改锚 returnAnchorPeriodId(最新已关账期)——【四项一起挪】,不能只挪一项,
        //   否则差额会全被「未归因」吸收(v1.6.30 的注释已经写明这个陷阱)。
        //   仍用加字段而不是改既有字段:openingBaselineLast / netWorthDelta 是存量卡在用的,
        //   动它们会连带改「本期怎么变」那张卡。
        /** v1.18.1 · 收益锚点期的净资产 Δ(vs 该期的上一期)· 无上一期则 null */
        BigDecimal returnAnchorDelta,
        /** v1.18.1 · 收益锚点期的开账基线(新纳入账户存量本金)· 可空 */
        BigDecimal returnAnchorOpeningBaseline
) {
    /** v0.4.2 加字段时的 backward-compat 构造器 · 老调用方继续传 7 参数 */
    public KpiSnapshot(BigDecimal netWorth, BigDecimal totalAssets, BigDecimal totalLiabilities,
                       BigDecimal emergencyFundMonths, BigDecimal debtToAssetRatio,
                       BigDecimal netWorthDelta, BigDecimal netWorthDeltaPct) {
        this(netWorth, totalAssets, totalLiabilities, emergencyFundMonths, debtToAssetRatio,
             netWorthDelta, netWorthDeltaPct, null, null, null, null, null, null, null, null, null);
    }

    /** v0.5.3 加 4 个透明化中间量时的 backward-compat 构造器 · 老调用方继续传 11 参数 */
    public KpiSnapshot(BigDecimal netWorth, BigDecimal totalAssets, BigDecimal totalLiabilities,
                       BigDecimal emergencyFundMonths, BigDecimal debtToAssetRatio,
                       BigDecimal netWorthDelta, BigDecimal netWorthDeltaPct,
                       BigDecimal monthlyPnlAmount, BigDecimal monthlyInvestReturnPct,
                       BigDecimal annualizedInvestReturnPct, BigDecimal ytdInvestPnl) {
        this(netWorth, totalAssets, totalLiabilities, emergencyFundMonths, debtToAssetRatio,
             netWorthDelta, netWorthDeltaPct, monthlyPnlAmount, monthlyInvestReturnPct,
             annualizedInvestReturnPct, ytdInvestPnl, null, null, null, null, null);
    }

    /** v0.13 加 openingBaselineLast 时的 backward-compat 构造器 · 老调用方继续传 15 参数 */
    public KpiSnapshot(BigDecimal netWorth, BigDecimal totalAssets, BigDecimal totalLiabilities,
                       BigDecimal emergencyFundMonths, BigDecimal debtToAssetRatio,
                       BigDecimal netWorthDelta, BigDecimal netWorthDeltaPct,
                       BigDecimal monthlyPnlAmount, BigDecimal monthlyInvestReturnPct,
                       BigDecimal annualizedInvestReturnPct, BigDecimal ytdInvestPnl,
                       BigDecimal liquidAssets, BigDecimal avgExpense,
                       BigDecimal prevNetWorth, BigDecimal lastNetInflow) {
        this(netWorth, totalAssets, totalLiabilities, emergencyFundMonths, debtToAssetRatio,
             netWorthDelta, netWorthDeltaPct, monthlyPnlAmount, monthlyInvestReturnPct,
             annualizedInvestReturnPct, ytdInvestPnl, liquidAssets, avgExpense,
             prevNetWorth, lastNetInflow, null);
    }

    /**
     * v1.6.30 加 3 个收益锚点字段时的 backward-compat 构造器 · 老调用方继续传 16 参数。
     * 默认视为「收益锚点 = 最后一期、无填报中提示」= v1.6.30 之前的行为。
     */
    public KpiSnapshot(BigDecimal netWorth, BigDecimal totalAssets, BigDecimal totalLiabilities,
                       BigDecimal emergencyFundMonths, BigDecimal debtToAssetRatio,
                       BigDecimal netWorthDelta, BigDecimal netWorthDeltaPct,
                       BigDecimal monthlyPnlAmount, BigDecimal monthlyInvestReturnPct,
                       BigDecimal annualizedInvestReturnPct, BigDecimal ytdInvestPnl,
                       BigDecimal liquidAssets, BigDecimal avgExpense,
                       BigDecimal prevNetWorth, BigDecimal lastNetInflow,
                       BigDecimal openingBaselineLast) {
        this(netWorth, totalAssets, totalLiabilities, emergencyFundMonths, debtToAssetRatio,
             netWorthDelta, netWorthDeltaPct, monthlyPnlAmount, monthlyInvestReturnPct,
             annualizedInvestReturnPct, ytdInvestPnl, liquidAssets, avgExpense,
             prevNetWorth, lastNetInflow, openingBaselineLast, netWorth, null, false, null,
             null, null, null, null,    // v1.10 · live 口径:老调用方不关心,补 null
             netWorthDelta, openingBaselineLast);   // v1.18.1 · 老调用方:归因锚点 = 最后一期(即改动前的行为)
    }

    /**
     * v1.18.1 加 2 个归因锚点字段时的 backward-compat 构造器 · 老调用方继续传 24 参数。
     * 默认「归因锚点 = 最后一期」= v1.18.1 之前的行为。
     */
    public KpiSnapshot(BigDecimal netWorth, BigDecimal totalAssets, BigDecimal totalLiabilities,
                       BigDecimal emergencyFundMonths, BigDecimal debtToAssetRatio,
                       BigDecimal netWorthDelta, BigDecimal netWorthDeltaPct,
                       BigDecimal monthlyPnlAmount, BigDecimal monthlyInvestReturnPct,
                       BigDecimal annualizedInvestReturnPct, BigDecimal ytdInvestPnl,
                       BigDecimal liquidAssets, BigDecimal avgExpense,
                       BigDecimal prevNetWorth, BigDecimal lastNetInflow,
                       BigDecimal openingBaselineLast,
                       BigDecimal returnAnchorNetWorth, java.time.LocalDate returnAnchorMonth,
                       boolean filingInProgress, Integer returnPeriodCount,
                       BigDecimal liveMonthlyPnlAmount, BigDecimal liveMonthlyInvestReturnPct,
                       BigDecimal liveIncome, BigDecimal liveExpense) {
        this(netWorth, totalAssets, totalLiabilities, emergencyFundMonths, debtToAssetRatio,
             netWorthDelta, netWorthDeltaPct, monthlyPnlAmount, monthlyInvestReturnPct,
             annualizedInvestReturnPct, ytdInvestPnl, liquidAssets, avgExpense,
             prevNetWorth, lastNetInflow, openingBaselineLast,
             returnAnchorNetWorth, returnAnchorMonth, filingInProgress, returnPeriodCount,
             liveMonthlyPnlAmount, liveMonthlyInvestReturnPct, liveIncome, liveExpense,
             netWorthDelta, openingBaselineLast);
    }
}
