package com.family.finance.web.dashboard;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodStatus;
import com.family.finance.factview.AccountPerformance;
import com.family.finance.factview.AllocationSlice;
import com.family.finance.factview.FactFilter;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.factview.KpiSnapshot;
import com.family.finance.factview.TrendPoint;
import com.family.finance.factview.WaterfallSegment;
import com.family.finance.domain.account.AccountClass;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.MemberMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.service.EntryService;
import com.family.finance.service.FamilyService;
import com.family.finance.service.FxService;
import com.family.finance.service.HouseholdCashflowService;
import com.family.finance.service.NavService;
import com.family.finance.service.goal.GoalProgressService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class DashboardController {
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0");

    private final FactViewService factViewService;
    private final FamilyService familyService;
    private final PeriodMapper periodMapper;
    private final AccountMapper accountMapper;
    private final MemberMapper memberMapper;
    private final EntryService entryService;
    private final NavService navService;
    private final FxService fxService;
    private final com.family.finance.service.MetricPrefsService metricPrefsService;   // v0.8 可配置指标
    private final com.family.finance.service.macro.MacroBenchmarkService macroBenchmarkService; // v0.5 FR-75
    private final GoalProgressService goalProgressService;
    private final HouseholdCashflowService householdCashflowService;
    private final com.family.finance.service.config.FamilyConfigService configService;
    private final com.family.finance.service.explain.MetricExplainService metricExplain; // v0.5.3 口径真实数值
    private final com.family.finance.service.insight.AssetInsightService assetInsightService; // v0.6 资产洞察速览

    @GetMapping("/dashboard")
    public String dashboard(@AuthenticationPrincipal MemberPrincipal me,
                            @RequestParam(defaultValue = "1Y") String range,
                            @RequestParam(required = false) String asof,
                            @RequestParam(name = "accounts", required = false) List<Long> accounts,
                            @RequestParam(required = false) String currency,
                            @RequestHeader(value = "HX-Request", required = false) String htmx,
                            Model model) {
        // v0.7 FR-133 兜底:零周期(全新部署)→ 回首页引导,避免 anchorPeriod() 抛异常 500
        if (periodMapper.countByFamily(me.getFamilyId()) == 0) {
            return "redirect:/";
        }
        String accountsCsv = accounts == null || accounts.isEmpty()
                ? null
                : accounts.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        populateModel(me, range, asof, accountsCsv, currency, model);
        if ("true".equalsIgnoreCase(htmx)) {
            return "dashboard/_region :: region";
        }
        return "dashboard/index";
    }

    private void populateModel(MemberPrincipal me, String range, String asof, String accountsCsv, String currency, Model model) {
        Family family = familyService.require(me.getFamilyId());
        // v0.8 FR-144:观察账期 as-of(默认最新,可选历史月)→ 作 rangeEnd,点状 KPI 随之
        List<Period> allPeriods = periodMapper.findAllByFamily(me.getFamilyId());
        Period anchor = resolveAsOf(asof, allPeriods, periodMapper.findCurrentOpen(me.getFamilyId()).orElse(null));
        List<Long> accountIds = parseAccountIds(accountsCsv);
        String viewCurrency = parseCurrency(currency, family.getBaseCurrency());
        FactFilter filter = new FactFilter(
                me.getFamilyId(),
                family.getPeriodType(),
                rangeStart(range, anchor.getPeriodStart()),
                anchor.getPeriodStart(),
                false,
                accountIds,
                viewCurrency
        );
        // BUG-FIX(2026-05-11 · critical):FactMapper.queryBase SQL 算 fx_to_base 时,
        // 任一非 base 账户币种 + 当期没 fx_rate 行 → 落 ELSE 1.0 兜底 → USD 余额被当 CNY 直接累加。
        // v0.8 BUG-FIX(v08-CCY-INV-2):MoM/YoY/趋势/TWR/本月资产收益率吃多期 endBalanceBase,
        //   只 ensure anchor 一期 → 上期/窗口期缺汇率未换算 → 切币种比值乱漂。改 ensure ≤anchor 全期。
        List<Long> ensurePeriodIds = allPeriods.stream()
                .filter(p -> p.getPeriodStart() != null && !p.getPeriodStart().isAfter(anchor.getPeriodStart()))
                .map(Period::getId)
                .toList();
        fxService.ensureForAccountCurrencies(me.getFamilyId(), family.getBaseCurrency(), ensurePeriodIds);

        // BUG-FIX(2026-05-10):viewCurrency 切换 → fx_rate 缺则即时拉 / 兜底 toast
        String requestedCurrency = viewCurrency;
        boolean fxFallback = false;
        if (!viewCurrency.equalsIgnoreCase(family.getBaseCurrency())) {
            boolean hasRate = fxService.getOrFetchRate(me.getFamilyId(), family.getBaseCurrency(), viewCurrency, anchor.getId()).isPresent();
            if (!hasRate) {
                viewCurrency = family.getBaseCurrency();
                fxFallback = true;
            } else {
                // v0.8 BUG-FIX(v08-CCY-INV-2):视图币种可能无账户(纯展示,如 HKD)→ ensureForAccountCurrencies 不覆盖。
                //   三角换算需每期都有 base→view 行,否则历史期落 1.0 → 切币种比值漂移。全窗口补 base→view。
                fxService.ensureRate(me.getFamilyId(), family.getBaseCurrency(), viewCurrency, ensurePeriodIds);
            }
        }
        FactFilter effectiveFilter = filter.viewCurrency().equals(viewCurrency)
                ? filter
                : new FactFilter(filter.familyId(), filter.periodType(), filter.rangeStart(),
                                 filter.rangeEnd(), filter.includeArchived(), filter.accountIds(), viewCurrency);

        FactSlice slice = factViewService.load(effectiveFilter);
        KpiSnapshot kpis = factViewService.kpis(slice);
        List<TrendPoint> trend = factViewService.netWorthTrend(slice);
        List<WaterfallSegment> waterfall = factViewService.incomeExpenseWaterfall(slice);
        List<AllocationSlice> allocation = factViewService.allocationByType(slice, slice.lastPeriodId());
        List<AccountPerformance> accountRows = factViewService.accountPerformance(slice);
        List<Account> allAccounts = accountMapper.findActiveByFamily(me.getFamilyId());
        Period currentOpen = periodMapper.findCurrentOpen(me.getFamilyId()).orElse(null);
        // v0.10 FR-165/166/167 · 本期「人赚 vs 钱赚」拆解 + 实时收支趋势(view 币种 · 含进行中本月)
        com.family.finance.factview.CashflowBreakdown cfBreak =
                factViewService.cashflowBreakdown(slice, slice.lastPeriodId());
        int cfFilled = householdCashflowService.filledMembersForPeriod(slice.lastPeriodId());
        int cfTotal = memberMapper.countActiveByFamily(me.getFamilyId());
        CashflowSplitView cashflowSplit = CashflowSplitView.of(kpis.netWorthDelta(), cfBreak, cfFilled, cfTotal);
        List<com.family.finance.factview.CashflowPoint> cashflowSeries =
                factViewService.cashflowSeries(slice, 6, currentOpen == null ? null : currentOpen.getId());
        // v0.8 FR-145:MoM/YoY 用 [as-of−12, as-of] 最小窗口算,与显示窗口解耦,缺对比期显数据不足
        com.family.finance.factview.MomYoy momYoy = factViewService.momYoy(new FactFilter(
                me.getFamilyId(), family.getPeriodType(),
                anchor.getPeriodStart().minusMonths(12), anchor.getPeriodStart(),
                false, accountIds, viewCurrency));

        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("range", normalizeRange(range));
        model.addAttribute("currency", viewCurrency);
        model.addAttribute("ranges", List.of("1M", "3M", "6M", "YTD", "1Y", "ALL"));
        // v0.8:as-of 账期选择(点状 KPI 随之)+ MoM/YoY
        model.addAttribute("asof", anchor.getPeriodStart().toString());
        model.addAttribute("periods", allPeriods);
        model.addAttribute("momYoy", momYoy);
        // v0.8 FR-149:账户列表按勾选的指标显示(必选项恒在)
        model.addAttribute("acctMetrics", metricPrefsService.enabled(family.getMetricPrefs(), "account"));
        model.addAttribute("famMetrics", metricPrefsService.enabled(family.getMetricPrefs(), "family"));
        model.addAttribute("currencies", List.of("CNY", "USD", "HKD"));
        model.addAttribute("accountsCsv", accountsCsv == null ? "" : accountsCsv);
        model.addAttribute("selectedAccountIds", accountIds == null ? java.util.Collections.<Long>emptyList() : accountIds);
        model.addAttribute("selectedAccountCount", accountIds == null ? allAccounts.size() : accountIds.size());
        model.addAttribute("allAccounts", allAccounts);
        model.addAttribute("anchorPeriod", anchor);
        model.addAttribute("currentOpen", currentOpen);
        model.addAttribute("pendingRows", currentOpen == null ? List.of() : entryService.listRows(me.getFamilyId(), me.getMemberId(), currentOpen, false).stream()
                .filter(row -> !row.done())
                .toList());

        // v0.3 FR-50d · 目标进度条带数据(失败容忍 · 不阻塞 dashboard 渲染)
        try {
            model.addAttribute("goalsProgress", goalProgressService.computeAll(me.getFamilyId()));
        } catch (Exception e) {
            model.addAttribute("goalsProgress", List.of());
        }

        model.addAttribute("kpis", kpis);
        // v0.5.3 · 计算指标真实数值(ⓘ tooltip)· viewCurrency 口径
        model.addAttribute("calc", metricExplain.dashboard(kpis, allocation, accountRows, viewCurrency));
        model.addAttribute("kpiNetWorth", money(viewCurrency, kpis.netWorth()));
        model.addAttribute("kpiAssets", money(viewCurrency, kpis.totalAssets()));
        model.addAttribute("kpiLiabilities", money(viewCurrency, kpis.totalLiabilities()));
        model.addAttribute("kpiEmergency", kpis.emergencyFundMonths() == null ? "—" : kpis.emergencyFundMonths().toPlainString() + " 月");
        model.addAttribute("kpiDebtRatio", percent(kpis.debtToAssetRatio()));
        model.addAttribute("kpiDelta", moneyDelta(viewCurrency, kpis.netWorthDelta()));
        // v0.3:优先用 period.total_*_input(用户在 /entry 第一步填的家庭口径)· fallback v0.2 cash_flow
        model.addAttribute("savingsRate", percent(householdCashflowService.currentSavingsRate(me.getFamilyId())));
        // v0.3 新增:月均支出 / 月均收入 KPI(per 用户反馈 2026-05-13 · 用最新口径)
        model.addAttribute("avgMonthlyExpense", money(viewCurrency, householdCashflowService.avgMonthlyExpense(me.getFamilyId())));
        model.addAttribute("avgMonthlyIncome", money(viewCurrency, householdCashflowService.avgMonthlyIncome(me.getFamilyId())));
        int[] filled = householdCashflowService.filledMonthRatio(me.getFamilyId());
        model.addAttribute("cashflowFilled", filled[0]);
        model.addAttribute("cashflowTotal", filled[1]);
        // v0.10 · 本期拆解卡 + 实时收支趋势(金额按 view 币种符号预格式化,结构/符号/宽度走视图模型)
        model.addAttribute("cashflowSplit", cashflowSplit);
        model.addAttribute("cashflowSeries", cashflowSeries);
        // v0.11.6 · 收支趋势仅在「有非零收支」时出图;近月全零(未填 PMC)→ 前端显空态细条,不留空白大卡
        boolean cashflowSeriesHasData = cashflowSeries != null && cashflowSeries.stream().anyMatch(p ->
                (p.income() != null && p.income().signum() != 0) || (p.expense() != null && p.expense().signum() != 0));
        model.addAttribute("cashflowSeriesHasData", cashflowSeriesHasData);
        model.addAttribute("cfDeltaLabel", moneyDelta(viewCurrency, cashflowSplit.deltaNetWorth()));
        model.addAttribute("cfRenLabel", moneyDelta(viewCurrency, cashflowSplit.renZhuan()));
        model.addAttribute("cfQianLabel", moneyDelta(viewCurrency, cashflowSplit.qianZhuan()));
        model.addAttribute("cfIncomeLabel", money(viewCurrency, cashflowSplit.income()));
        model.addAttribute("cfExpenseLabel", money(viewCurrency, cashflowSplit.expense()));

        model.addAttribute("trend", trend);
        model.addAttribute("allocation", allocation);
        model.addAttribute("waterfall", waterfall);
        // v0.6 · 资产洞察速览(仅硬数据 · 不调 LLM · 保持 dashboard 轻快)· compute 永不抛
        model.addAttribute("insight", assetInsightService.compute(me.getFamilyId()));
        model.addAttribute("accountRows", accountRows);
        model.addAttribute("fxFallback", fxFallback);
        model.addAttribute("requestedCurrency", requestedCurrency);

        // v0.4 FR-61a / v0.5 FR-75 · CPI 假设默认改用真实剔极端值几何均值(替代硬编码 2%)
        // 用户在 dashboard 切换器显式选过 → 用其值;否则用宏观真实 CPI;宏观缺失再退 2%。
        BigDecimal realCpi = macroBenchmarkService.cpiAverages().defaultValue();
        model.addAttribute("cpiAssumption", family.getCpiAssumption() != null
            ? family.getCpiAssumption()
            : (realCpi != null && realCpi.signum() > 0 ? realCpi : new BigDecimal("2.00")));
        // v0.5 FR-75 · M2 地位线(剔极端值几何均值)· 前端加一条参考线 · 详看 /reports 财富水位
        BigDecimal realM2 = macroBenchmarkService.m2Averages().defaultValue();
        model.addAttribute("m2Assumption", realM2 != null && realM2.signum() > 0 ? realM2 : new BigDecimal("8.00"));

        // v0.4 FR-60a · 月储蓄能力(从 v0.3 储蓄区拉)+ 收 KPI
        model.addAttribute("monthlySavingsCapacity",
            money(viewCurrency, householdCashflowService.medianMonthlySavings(me.getFamilyId())));

        // v0.4.2 · 「钱赚的」二分指标 · 本月资产收益(剔除外部现金流)
        // 顶替"月储蓄能力"为第 5 KPI · 储蓄能力保留在 /reports 储蓄区
        model.addAttribute("monthlyPnlMoney",
            kpis.monthlyPnlAmount() == null ? "—"
                : (kpis.monthlyPnlAmount().signum() >= 0 ? "+" : "−")
                  + money(viewCurrency, kpis.monthlyPnlAmount().abs()).replaceFirst("^[+−]", ""));
        model.addAttribute("monthlyPnlPctLabel",
            kpis.monthlyInvestReturnPct() == null ? "—"
                : String.format("%+.2f%%", kpis.monthlyInvestReturnPct().doubleValue() * 100));
        model.addAttribute("annualizedInvestReturnLabel",
            kpis.annualizedInvestReturnPct() == null ? "—"
                : String.format("%+.2f%%", kpis.annualizedInvestReturnPct().doubleValue() * 100));

        // v0.4 FR-62c · 应急金不闲置评估(用 LIQUID 类账户合计 vs 应急需求 × 1.5x)
        Long lastPid = slice.lastPeriodId();
        BigDecimal liquidAssets = slice.rows().stream()
            .filter(r -> java.util.Objects.equals(r.periodId(), lastPid))
            .filter(r -> r.accountLiquidity() == com.family.finance.domain.account.AccountLiquidity.LIQUID)
            .map(r -> r.endBalanceBase() == null ? BigDecimal.ZERO : r.endBalanceBase())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avgExpense = householdCashflowService.avgMonthlyExpense(me.getFamilyId());
        // v0.4.18 · 应急月数 + buffer 倍率改读 ConfigService(默认 6 月 / 1.5x)
        int emergencyMonths = configService.getInt(me.getFamilyId(),
            com.family.finance.service.config.FamilyConfigService.K_EMERGENCY_MONTHS,
            com.family.finance.calc.LiquiditySurplus.DEFAULT_EMERGENCY_MONTHS);
        BigDecimal liquidMultiplier = BigDecimal.valueOf(configService.getDouble(me.getFamilyId(),
            com.family.finance.service.config.FamilyConfigService.K_LIQUID_BUFFER, 1.5));
        var liquidSurplus = com.family.finance.calc.LiquiditySurplus.evaluate(
            liquidAssets, avgExpense, emergencyMonths, liquidMultiplier);
        model.addAttribute("liquidSurplus", liquidSurplus);
        model.addAttribute("liquidSurplusMoney", money(viewCurrency, liquidSurplus.surplus()));

        model.addAttribute("trendLabels", trend.stream().map(TrendPoint::label).toList());
        model.addAttribute("trendValues", trend.stream().map(TrendPoint::value).toList());
        model.addAttribute("incomeValues", waterfall.stream().map(WaterfallSegment::income).toList());
        model.addAttribute("expenseValues", waterfall.stream().map(WaterfallSegment::expense).toList());
        model.addAttribute("savingsValues", waterfall.stream().map(this::savingsRatePoint).toList());
        model.addAttribute("allocationLabels", allocation.stream().map(AllocationSlice::label).toList());
        model.addAttribute("allocationValues", allocation.stream().map(AllocationSlice::value).toList());

        // v0.2.1(2026-05-11)· 按成员维度的资产分布饼图(LOAN 不计,跟资产配置一致)
        var memberAlloc = computeMemberAllocation(me.getFamilyId(), allAccounts, accountRows);
        model.addAttribute("memberAllocationLabels", memberAlloc.keySet().stream().toList());
        model.addAttribute("memberAllocationValues", memberAlloc.values().stream().toList());
    }

    /**
     * 按 account.primary_owner_member_id 聚合资产(LOAN 排除)。
     * NULL → "共同";有值 → member.display_name(查不到的 fallback "成员#{id}")。
     */
    private java.util.LinkedHashMap<String, java.math.BigDecimal> computeMemberAllocation(
            long familyId, List<Account> allAccounts, List<AccountPerformance> accountRows) {
        java.util.Map<Long, String> nameById = memberMapper.findActiveByFamily(familyId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.family.finance.domain.member.Member::getId,
                        com.family.finance.domain.member.Member::getDisplayName));
        java.util.Map<Long, Account> accById = allAccounts.stream()
                .collect(java.util.stream.Collectors.toMap(Account::getId, java.util.function.Function.identity()));
        java.util.LinkedHashMap<String, java.math.BigDecimal> byOwner = new java.util.LinkedHashMap<>();
        for (AccountPerformance ap : accountRows) {
            if (ap.accountType() == null) continue;
            if (com.family.finance.factview.FactProjector.classOf(ap.accountType()) == AccountClass.LIABILITY) continue;
            if (ap.currentValue() == null) continue;
            Account acc = accById.get(ap.accountId());
            if (acc == null) continue;
            String label = acc.getPrimaryOwnerMemberId() == null
                    ? "共同"
                    : nameById.getOrDefault(acc.getPrimaryOwnerMemberId(), "成员#" + acc.getPrimaryOwnerMemberId());
            byOwner.merge(label, ap.currentValue(), java.math.BigDecimal::add);
        }
        return byOwner;
    }


    /**
     * Dashboard 锚点 = 最新一期(无论 OPEN/CLOSED)。
     * <p>v0.1 的旧实现优先取「最新 CLOSED」,导致开了新月后 dashboard 始终显示上个月数据 ——
     * 这与"实时汇总"产品定位冲突,2026-05-10 改为永远取最新一期。
     */
    private Period anchorPeriod(long familyId) {
        return periodMapper.findLatest(familyId, 1).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("尚未创建周期"));
    }

    /**
     * v0.8:解析观察账期 as-of。命中 asof 串则用之;否则默认 = **当前账期**(与主页/填报一致):
     * 优先当前 OPEN 期,否则取最近一个已开始(period_start ≤ 今天)的期,
     * 都没有再退回 max —— 避免锚到 dev/未来的 stray 期(如 beta 的 2034-01)。
     */
    private Period resolveAsOf(String asof, List<Period> all, Period openPeriod) {
        if (asof != null && !asof.isBlank()) {
            for (Period p : all) {
                if (p.getPeriodStart() != null && p.getPeriodStart().toString().equals(asof)) return p;
            }
        }
        if (openPeriod != null) return openPeriod;
        java.time.LocalDate today = java.time.LocalDate.now();
        return all.stream()
                .filter(p -> p.getPeriodStart() != null && !p.getPeriodStart().isAfter(today))
                .max(java.util.Comparator.comparing(Period::getPeriodStart))
                .orElseGet(() -> all.stream().max(java.util.Comparator.comparing(Period::getPeriodStart))
                        .orElseThrow(() -> new IllegalStateException("尚未创建周期")));
    }

    private LocalDate rangeStart(String range, LocalDate anchor) {
        return switch (normalizeRange(range)) {
            case "1M" -> anchor;
            case "3M" -> anchor.minusMonths(2);
            case "6M" -> anchor.minusMonths(5);
            case "YTD" -> anchor.withDayOfYear(1);
            case "ALL" -> LocalDate.of(1970, 1, 1);
            default -> anchor.minusMonths(11);
        };
    }

    private String normalizeRange(String range) {
        if (range == null) {
            return "1Y";
        }
        return switch (range.toUpperCase()) {
            case "1M", "3M", "6M", "YTD", "ALL" -> range.toUpperCase();
            default -> "1Y";
        };
    }

    private List<Long> parseAccountIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return null;
        }
        List<Long> ids = Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(Long::parseLong)
                .distinct()
                .toList();
        return ids.isEmpty() ? null : ids;
    }

    private String parseCurrency(String currency, String fallback) {
        if (currency == null || currency.isBlank()) {
            return fallback;
        }
        return switch (currency.toUpperCase()) {
            case "CNY", "USD", "HKD" -> currency.toUpperCase();
            default -> fallback;
        };
    }

    private BigDecimal savingsRatePoint(WaterfallSegment segment) {
        if (segment.income().signum() == 0) {
            return null;
        }
        return segment.income().subtract(segment.expense())
                .divide(segment.income(), 6, RoundingMode.HALF_EVEN)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_EVEN);
    }

    private String money(String currency, BigDecimal amount) {
        if (amount == null) {
            return "—";
        }
        String symbol = switch (currency) {
            case "USD" -> "$";
            case "HKD" -> "HK$";
            default -> "¥";
        };
        return symbol + MONEY.format(amount.setScale(0, RoundingMode.HALF_UP));
    }

    private String moneyDelta(String currency, BigDecimal amount) {
        if (amount == null) {
            return "—";
        }
        return (amount.signum() >= 0 ? "+" : "") + money(currency, amount);
    }

    private String percent(BigDecimal ratio) {
        if (ratio == null) {
            return "—";
        }
        return ratio.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
