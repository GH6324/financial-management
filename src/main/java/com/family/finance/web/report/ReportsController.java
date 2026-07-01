package com.family.finance.web.report;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.fx.FxRate;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodStatus;
import com.family.finance.factview.AccountPeriodFact;
import com.family.finance.factview.AccountPerformance;
import com.family.finance.factview.DecompositionPoint;
import com.family.finance.factview.FactFilter;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.factview.TrendPoint;
import com.family.finance.factview.WaterfallSegment;
import com.family.finance.calc.BenchmarkAggregator;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.FxMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.service.FamilyService;
import com.family.finance.service.FxService;
import com.family.finance.service.NavService;
import com.family.finance.service.ProductCategoryService;
import com.family.finance.service.allocation.AllocationService;
import com.family.finance.service.checkup.FamilyDiagnose;
import com.family.finance.service.checkup.FamilyDiagnoseService;
import com.family.finance.service.HouseholdCashflowService;
import com.family.finance.service.goal.GoalProgressService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class ReportsController {
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0");

    private final FactViewService factViewService;
    private final FamilyService familyService;
    private final PeriodMapper periodMapper;
    private final AccountMapper accountMapper;
    private final FxMapper fxMapper;
    private final FxService fxService;
    private final NavService navService;
    private final FamilyDiagnoseService familyDiagnoseService;
    private final GoalProgressService goalProgressService;
    private final HouseholdCashflowService householdCashflowService;
    // v0.4 新依赖
    private final ProductCategoryService productCategoryService;
    private final AllocationService allocationService;
    private final com.family.finance.repository.AllocationAnchorMapper allocationAnchorMapper;
    private final com.family.finance.repository.RebalanceAdviceCacheMapper rebalanceAdviceCacheMapper;
    // v0.5 FR-72/73/74 · 财富水位
    private final com.family.finance.service.macro.WaterLevelService waterLevelService;
    private final com.family.finance.service.macro.MacroBenchmarkService macroBenchmarkService;
    private final com.family.finance.service.explain.MetricExplainService metricExplain; // v0.5.3 口径真实数值
    private final com.family.finance.service.MetricPrefsService metricPrefsService; // v0.11.4 账户表复用管理页指标配置
    private final com.fasterxml.jackson.databind.ObjectMapper jacksonMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @GetMapping("/reports")
    public String reports(@AuthenticationPrincipal MemberPrincipal me,
                          @RequestParam(defaultValue = "1Y") String range,
                          @RequestParam(name = "accounts", required = false) List<Long> accounts,
                          @RequestParam(required = false) String currency,
                          @RequestParam(required = false) String asof, // v0.11.5 · 观察账期(只在已关账期里选)
                          @RequestHeader(value = "HX-Request", required = false) String htmx,
                          Model model) {
        String accountsCsv = accounts == null || accounts.isEmpty()
                ? null
                : accounts.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        populateModel(me, range, accountsCsv, currency, asof, model);
        if ("true".equalsIgnoreCase(htmx)) {
            return "reports/_region :: region";
        }
        return "reports/index";
    }

    @GetMapping("/reports/period/{periodId}")
    public String periodDrilldown(@AuthenticationPrincipal MemberPrincipal me,
                                  @PathVariable long periodId,
                                  Model model) {
        Family family = familyService.require(me.getFamilyId());
        Period period = periodMapper.findById(periodId)
                .filter(p -> p.getFamilyId() == me.getFamilyId())
                .orElseThrow(() -> new IllegalArgumentException("周期不存在: " + periodId));
        FactSlice slice = factViewService.load(new FactFilter(
                me.getFamilyId(), period.getPeriodType(), period.getPeriodStart(), period.getPeriodStart(),
                false, null, family.getBaseCurrency()));
        List<AccountPeriodFact> rows = slice.rows().stream()
                .filter(row -> row.periodId().equals(periodId))
                .toList();
        model.addAttribute("period", period);
        model.addAttribute("rows", rows);
        model.addAttribute("currency", family.getBaseCurrency());
        return "reports/_drilldown :: modal";
    }

    private void populateModel(MemberPrincipal me, String range, String accountsCsv, String currency, String asof, Model model) {
        Family family = familyService.require(me.getFamilyId());
        // v0.5.5 FR-94 · 报表锚定「最近已关账(≤今天)账期」快照;无则退外壳锚 + closedSnapshot=false
        // v0.11.5 · 观察账期:报表是每月快照,可在「已关账账期」里回看任一期(asof 命中则锚它,否则默认最近已关账)
        ReportsAnchorResolver.AnchorChoice defaultChoice = resolveAnchor(me.getFamilyId());
        // v0.11.5 · 可回看的已关账期 = CLOSED 且 ≤ 默认锚(最近已关账)。以「默认锚」作上界(而非 LocalDate.now())——
        //   resolveAnchor 走 DB 日期挑锚,若 JVM 与 DB 日期有偏差,用 now 作上界会把默认锚挤出下拉;用锚作界则默认锚必在列。
        List<Period> closedPeriods = defaultChoice.closedSnapshot()
                ? periodMapper.findAllByFamily(me.getFamilyId()).stream()
                    .filter(p -> p.getStatus() == com.family.finance.domain.period.PeriodStatus.CLOSED
                            && p.getPeriodStart() != null
                            && !p.getPeriodStart().isAfter(defaultChoice.anchor().getPeriodStart()))
                    .sorted(java.util.Comparator.comparing(Period::getPeriodStart).reversed())
                    .toList()
                : java.util.List.of();
        // asof 命中某已关账期 → 锚它(closedSnapshot=true);否则用默认锚
        ReportsAnchorResolver.AnchorChoice anchorChoice = defaultChoice;
        if (asof != null && !asof.isBlank()) {
            for (Period p : closedPeriods) {
                if (asof.equals(p.getPeriodStart().toString())) {
                    anchorChoice = new ReportsAnchorResolver.AnchorChoice(p, true);
                    break;
                }
            }
        }
        Period anchor = anchorChoice.anchor();
        boolean closedSnapshot = anchorChoice.closedSnapshot();
        List<Long> accountIds = parseAccountIds(accountsCsv);
        String viewCurrency = parseCurrency(currency, family.getBaseCurrency());
        // BUG-FIX(2026-05-11 · critical):非 base 账户币种 → 当期 fx_rate 必须存在,不然 SQL 走 1.0 兜底
        // v0.8 BUG-FIX(v08-CCY-INV-2):报表趋势/TWR/同比也吃多期 endBalanceBase,ensure 扩到 ≤anchor 全期
        List<Long> ensurePeriodIds = periodMapper.findAllByFamily(me.getFamilyId()).stream()
                .filter(p -> p.getPeriodStart() != null && !p.getPeriodStart().isAfter(anchor.getPeriodStart()))
                .map(Period::getId)
                .toList();
        fxService.ensureForAccountCurrencies(me.getFamilyId(), family.getBaseCurrency(), ensurePeriodIds);

        // BUG-FIX(2026-05-10):同 dashboard,缺 fx_rate 时即时拉 frankfurter,失败再回退 + toast 提示
        String requestedCurrency = viewCurrency;
        boolean fxFallback = false;
        if (!viewCurrency.equalsIgnoreCase(family.getBaseCurrency())) {
            boolean hasRate = fxService.getOrFetchRate(me.getFamilyId(), family.getBaseCurrency(), viewCurrency, anchor.getId()).isPresent();
            if (!hasRate) {
                viewCurrency = family.getBaseCurrency();
                fxFallback = true;
            } else {
                // v0.8 BUG-FIX(v08-CCY-INV-2):视图币种(可能无账户)全窗口补 base→view,三角换算不漏期
                fxService.ensureRate(me.getFamilyId(), family.getBaseCurrency(), viewCurrency, ensurePeriodIds);
            }
        }
        FactSlice slice = factViewService.load(new FactFilter(
                me.getFamilyId(),
                family.getPeriodType(),
                rangeStart(range, anchor.getPeriodStart()),
                anchor.getPeriodStart(),
                false,
                accountIds,
                viewCurrency
        ));
        // v0.4 FR-60b · waterfall + sankey 数据准备移除(流水视角)
        // 但 decomposition labels 仍需(给本金 vs 收益分解图用 · 复用 waterfall.label 输出)
        List<WaterfallSegment> waterfall = factViewService.incomeExpenseWaterfall(slice);
        List<DecompositionPoint> decomposition = factViewService.principalVsReturnDecomposition(slice);
        List<TrendPoint> debtTrend = factViewService.debtTrend(slice);
        List<AccountPerformance> accountRows = factViewService.accountPerformance(slice);
        DecompositionPoint lastDecomposition = decomposition.isEmpty() ? null : decomposition.getLast();
        List<Account> allAccounts = accountMapper.findActiveByFamily(me.getFamilyId());
        List<FxRate> fxRates = fxMapper.findLatestByFamily(me.getFamilyId(), 36);

        // v0.4 FR-61b · 账户级 vs 基准对照
        java.util.Map<Long, String> pcCodeByAccountId = new java.util.HashMap<>();
        for (Account a : allAccounts) {
            if (a.getProductCategoryCode() != null) pcCodeByAccountId.put(a.getId(), a.getProductCategoryCode());
        }
        java.util.Map<String, BigDecimal> benchmarkPctByPcCode = new java.util.HashMap<>();
        for (var pc : productCategoryService.listAll()) {
            if (pc.getBenchmarkPct() != null) benchmarkPctByPcCode.put(pc.getCode(), pc.getBenchmarkPct());
        }
        // v0.11.4 · 账户表改为「复用管理页指标配置」渲染:直接迭代全字段 accountRows(AccountPerformance),
        //   基准对照数据按 accountId 建索引 map 供模板 zip;不再压成精简的 AccountBenchmarkRow 列表。
        java.util.Map<Long, AccountBenchmarkRow> benchmarkByAccount = new java.util.HashMap<>();
        for (AccountPerformance ap : accountRows) {
            String pcCode = pcCodeByAccountId.get(ap.accountId());
            BigDecimal pcBench = pcCode == null ? null : benchmarkPctByPcCode.get(pcCode);
            BigDecimal benchmark = BenchmarkAggregator.benchmarkForAccount(
                ap.xirr(), pcBench, ap.accountType().name());
            // v0.11.4:实际 = 卡片显示的那个 xirr(<12 期累计 / ≥12 期年化)− 同基基准 → pp;
            //   修 v0.10.5「cumPnl/净投入 当实际」的爆值(净投入极小→+19497pp)+ 与显示脱节。
            int months = ap.monthsHeld() == null ? 0 : ap.monthsHeld();
            BigDecimal diffPct = BenchmarkAggregator.displayedDiffPercentPoints(ap.xirr(), benchmark, months);
            BenchmarkAggregator.BeatStatus beat = BenchmarkAggregator.beatStatusDisplayed(diffPct, months);
            benchmarkByAccount.put(ap.accountId(), new AccountBenchmarkRow(
                ap.accountName(), ap.accountType().name(), pcCode,
                null, benchmark, diffPct, beat.name(), null)); // xirrLabel/valueLabel 模板内实时格式化,置 null
        }
        java.util.Set<String> acctMetrics = metricPrefsService.enabled(family.getMetricPrefs(), "account");

        // v0.4 FR-61c · 家庭加权基准
        java.util.List<BenchmarkAggregator.BenchmarkInput> bmInputs = slice.rows().stream()
            .filter(r -> java.util.Objects.equals(r.periodId(), slice.lastPeriodId()))
            .filter(r -> r.endBalanceBase() != null && r.endBalanceBase().signum() > 0)
            .map(r -> {
                String pcCode = pcCodeByAccountId.get(r.accountId());
                BigDecimal pcBench = pcCode == null ? null : benchmarkPctByPcCode.get(pcCode);
                BigDecimal benchmark = BenchmarkAggregator.benchmarkForAccount(
                    null, pcBench, r.accountType().name());
                return new BenchmarkAggregator.BenchmarkInput(r.endBalanceBase(), benchmark);
            })
            .toList();
        BigDecimal familyBenchmarkPct = BenchmarkAggregator.weightedFamilyBenchmark(bmInputs);
        BigDecimal familyXirrDecimal = factViewService.familyXirr(slice);
        // v0.11.4:家庭 pill 实际 = 卡片头条显示的那个「家庭 XIRR」本身(<12 期累计 / ≥12 期年化)− 加权基准 → pp。
        //   修 v0.10.5「累计PnL/累计净投入 当实际」的爆值 + 与头条 XIRR 脱节(头条 8.3% 却显示跑输 -243%)。
        int familyMonths = slice.periodIds().size();
        BigDecimal familyDiffPct = BenchmarkAggregator.displayedDiffPercentPoints(familyXirrDecimal, familyBenchmarkPct, familyMonths);
        BenchmarkAggregator.BeatStatus familyBeat = BenchmarkAggregator.beatStatusDisplayed(familyDiffPct, familyMonths);

        // v0.4 FR-62a · 配置 diff
        AllocationService.DiffResult allocationDiff = allocationService.compute(me.getFamilyId(), slice);
        java.util.List<com.family.finance.domain.allocation.AllocationAnchor> allocationAnchors = allocationAnchorMapper.findAll();

        // v0.4 FR-62b · 调仓建议缓存渲染(若有)
        var f4cache = rebalanceAdviceCacheMapper.findByFamilyAndAnchor(me.getFamilyId(), family.getAllocationAnchor());
        RebalanceAdviceView rebalanceAdvice = null;
        if (f4cache.isPresent()) {
            var cache = f4cache.get();
            // 30 天 TTL 检查
            long days = java.time.Duration.between(cache.getGeneratedAt(), java.time.LocalDateTime.now()).toDays();
            if (days <= 30) {
                try {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> raw = jacksonMapper.readValue(cache.getContentJson(), java.util.Map.class);
                    @SuppressWarnings("unchecked")
                    java.util.List<java.util.Map<String, Object>> actions =
                        (java.util.List<java.util.Map<String, Object>>) raw.getOrDefault("actions", java.util.List.of());
                    rebalanceAdvice = new RebalanceAdviceView(
                        (String) raw.get("narrative"),
                        actions,
                        cache.getGeneratedAt());
                } catch (Exception ignored) { /* 解析失败不渲染 */ }
            }
        }

        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("range", normalizeRange(range));
        model.addAttribute("currency", viewCurrency);
        model.addAttribute("ranges", List.of("1M", "3M", "6M", "YTD", "1Y", "ALL"));
        model.addAttribute("currencies", List.of("CNY", "USD", "HKD"));
        model.addAttribute("accountsCsv", accountsCsv == null ? "" : accountsCsv);
        model.addAttribute("selectedAccountCount", accountIds == null ? allAccounts.size() : accountIds.size());
        model.addAttribute("allAccounts", allAccounts);
        model.addAttribute("anchorPeriod", anchor);
        // v0.11.5 · 观察账期下拉:只列已关账账期;asof=当前锚(仅快照态非空,外壳态留空 → 下拉不选中)
        model.addAttribute("periods", closedPeriods);
        model.addAttribute("asof", closedSnapshot ? anchor.getPeriodStart().toString() : "");

        BigDecimal familyTwrDecimal = factViewService.familyTwr(slice);
        // v0.5.5 FR-95 · 四指标需 ≥2 个已关账账期才有意义(要上一期做基准);不足 → 显「—」不显误导性 0
        boolean reportsHasMetrics = closedSnapshot && slice.periodIds().size() >= 2;
        model.addAttribute("closedSnapshot", closedSnapshot);
        model.addAttribute("reportsHasMetrics", reportsHasMetrics);
        // v0.10.5 · 资产年化 仅满 12 期才是真年化(12月滚动几何);不足为累计 → 动态标签「资产累计」
        model.addAttribute("familyReturnAnnualized", familyMonths >= 12);
        if (reportsHasMetrics) {
            model.addAttribute("familyXirr", percent(familyXirrDecimal));
            model.addAttribute("familyTwr", percent(familyTwrDecimal));
            model.addAttribute("cumulativeNetInflow", money(viewCurrency, lastDecomposition == null ? BigDecimal.ZERO : lastDecomposition.cumulativeNetInflow()));
            model.addAttribute("cumulativePnl", money(viewCurrency, lastDecomposition == null ? BigDecimal.ZERO : lastDecomposition.cumulativePnl()));
        } else {
            model.addAttribute("familyXirr", "—");
            model.addAttribute("familyTwr", "—");
            model.addAttribute("cumulativeNetInflow", "—");
            model.addAttribute("cumulativePnl", "—");
        }

        // v0.5 FR-72/73/74 · 财富水位(并入 reports section)· 用净资产趋势 + CPI/M2 基准
        List<TrendPoint> trend = factViewService.netWorthTrend(slice);
        var waterLevel = waterLevelService.compute(trend);
        model.addAttribute("waterLevel", waterLevel);
        model.addAttribute("cpiAverages", macroBenchmarkService.cpiAverages());
        model.addAttribute("m2Averages", macroBenchmarkService.m2Averages());
        model.addAttribute("macroLatest", macroBenchmarkService.latest());
        // 人赚/钱赚原始 BigDecimal(给水位分解诊断用 · 复用 FR-84 修复后的口径)
        model.addAttribute("netInflowRaw", lastDecomposition == null ? BigDecimal.ZERO : lastDecomposition.cumulativeNetInflow());
        model.addAttribute("pnlRaw", lastDecomposition == null ? BigDecimal.ZERO : lastDecomposition.cumulativePnl());

        // v0.4 FR-60b · 砍 waterfall / sankey 数据(不再注入)
        model.addAttribute("decomposition", decomposition);
        model.addAttribute("debtTrend", debtTrend);
        // v0.11.4 · 账户表复用管理页指标配置:注入全字段 accountRows + 指标启用集 + 基准索引 + 类目索引
        model.addAttribute("accountRows", accountRows);
        model.addAttribute("acctMetrics", acctMetrics);
        model.addAttribute("benchmarkByAccount", benchmarkByAccount);
        model.addAttribute("pcCodeByAccount", pcCodeByAccountId);
        model.addAttribute("fxRates", fxRates);
        model.addAttribute("fxFallback", fxFallback);
        model.addAttribute("requestedCurrency", requestedCurrency);

        // v0.4 FR-61c · 家庭 vs 基准 · v0.5.5:无快照指标时置 null → 隐藏 vs 基准 pill(不在「—」旁显比较)
        model.addAttribute("familyBenchmarkPct", reportsHasMetrics ? familyBenchmarkPct : null);
        model.addAttribute("familyBenchmarkDiff", familyDiffPct);
        model.addAttribute("familyBeatStatus", familyBeat.name());

        // v0.4 FR-62a · 配置 diff
        model.addAttribute("allocationDiff", allocationDiff);
        model.addAttribute("allocationAnchors", allocationAnchors);
        // v0.4 FR-62b · 调仓建议
        model.addAttribute("rebalanceAdvice", rebalanceAdvice);

        // labels = 全部账期标签(N 期)· 修 bug:原来错接成 decomposition 标签(N−1 期)导致
        //   负债曲线(用 data.labels + N 个 debtValues)少一个标签 → 只画 N−1 点;
        //   分解图(用 data.labels.slice(1) 对齐 N−1 个分解点)再少一个 → N−2 柱(2 期时 0 柱)。
        //   改用全期标签后:负债曲线 N 点、分解图 slice(1) 正好对齐 N−1 个分解点。
        model.addAttribute("labels", debtTrend.stream().map(TrendPoint::label).toList());
        model.addAttribute("decompPrincipal", decomposition.stream().map(DecompositionPoint::cumulativeNetInflow).toList());
        model.addAttribute("decompPnl", decomposition.stream().map(DecompositionPoint::cumulativePnl).toList());
        model.addAttribute("debtValues", debtTrend.stream().map(TrendPoint::value).toList());

        // v0.2 FR-40e · 风险等级分布(用于 reports 风险敞口环形图)
        FamilyDiagnose familyDiagnose = familyDiagnoseService.diagnose(me.getFamilyId());
        model.addAttribute("riskDistribution", familyDiagnose.riskDistribution());
        model.addAttribute("riskLabels", familyDiagnose.riskDistribution().stream()
                .map(FamilyDiagnose.RiskBucket::label).toList());
        model.addAttribute("riskValues", familyDiagnose.riskDistribution().stream()
                .map(FamilyDiagnose.RiskBucket::amount).toList());
        model.addAttribute("riskRatios", familyDiagnose.riskDistribution().stream()
                .map(b -> b.ratio() == null ? BigDecimal.ZERO
                        : b.ratio().multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_EVEN))
                .toList());

        // v0.3 FR-51a/b · 储蓄能力 · 月度双柱(2026-05-13 修订:成员级 SUM 聚合)
        // v0.5.3 · 同时把中间量 stash 到 local,供下方 tooltip 真实数值(储蓄区按本位币 ¥)
        boolean savAvail = false;
        int savFilled = 0, savTotal = 0;
        BigDecimal savSumInc = BigDecimal.ZERO, savSumExp = BigDecimal.ZERO,
                   savAvgInc = null, savAvgExp = null, savLatestInc = null, savLatestExp = null,
                   savRateDec = null, savMedian = null;
        try {
            // 近 12 期家庭聚合 · 升序排列给图表用
            var aggs = householdCashflowService.findRecentAggregates(me.getFamilyId(), 12);
            var sortedAggs = aggs.stream()
                .sorted((a, b) -> a.periodStart().compareTo(b.periodStart()))
                .toList();
            List<String> savLabels = sortedAggs.stream()
                .map(a -> a.periodStart().toString().substring(2, 7)).toList();
            List<BigDecimal> savIncome = sortedAggs.stream().map(a -> a.totalIncome()).toList();
            List<BigDecimal> savExpense = sortedAggs.stream().map(a -> a.totalExpense()).toList();
            int[] ratio = householdCashflowService.filledMonthRatio(me.getFamilyId());
            savAvgInc = householdCashflowService.avgMonthlyIncome(me.getFamilyId());
            savAvgExp = householdCashflowService.avgMonthlyExpense(me.getFamilyId());
            savMedian = householdCashflowService.medianMonthlySavings(me.getFamilyId());
            savRateDec = householdCashflowService.currentSavingsRate(me.getFamilyId());
            model.addAttribute("savingsLabels", savLabels);
            model.addAttribute("savingsIncome", savIncome);
            model.addAttribute("savingsExpense", savExpense);
            model.addAttribute("savingsMonthlyMedian", savMedian);
            model.addAttribute("savingsRate", savRateDec);
            model.addAttribute("avgMonthlyExpense", savAvgExp);
            model.addAttribute("avgMonthlyIncome", savAvgInc);
            model.addAttribute("savingsFilledMonths", ratio[0]);
            model.addAttribute("savingsTotalMonths", ratio[1]);
            model.addAttribute("savingsAvailable", ratio[0] > 0);
            model.addAttribute("goalsProgress", goalProgressService.computeAll(me.getFamilyId()));
            // stash for tooltip
            savAvail = ratio[0] > 0;
            savFilled = ratio[0];
            savTotal = ratio[1];
            for (BigDecimal x : savIncome) if (x != null) savSumInc = savSumInc.add(x);
            for (BigDecimal x : savExpense) if (x != null) savSumExp = savSumExp.add(x);
            if (!sortedAggs.isEmpty()) {
                var latest = sortedAggs.get(sortedAggs.size() - 1);
                savLatestInc = latest.totalIncome();
                savLatestExp = latest.totalExpense();
            }
        } catch (Exception e) {
            model.addAttribute("savingsAvailable", false);
            model.addAttribute("savingsFilledMonths", 0);
            model.addAttribute("savingsTotalMonths", 0);
            model.addAttribute("savingsLabels", List.of());
            model.addAttribute("savingsIncome", List.of());
            model.addAttribute("savingsExpense", List.of());
            model.addAttribute("goalsProgress", List.of());
            savAvail = false;
        }

        // v0.5.3 · 计算指标真实数值(ⓘ tooltip)· KPI 区 viewCurrency · 储蓄区本位币
        BigDecimal firstNW = trend.isEmpty() ? null : trend.get(0).value();
        BigDecimal lastNW = trend.isEmpty() ? null : trend.get(trend.size() - 1).value();
        String firstLabel = trend.isEmpty() ? null : trend.get(0).label();
        String lastLabel = trend.isEmpty() ? null : trend.get(trend.size() - 1).label();
        BigDecimal bmTotalBal = bmInputs.stream()
                .map(BenchmarkAggregator.BenchmarkInput::balanceBase)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cumNetInflow = lastDecomposition == null ? BigDecimal.ZERO : lastDecomposition.cumulativeNetInflow();
        BigDecimal cumPnl = lastDecomposition == null ? BigDecimal.ZERO : lastDecomposition.cumulativePnl();
        var reportsInputs = new com.family.finance.service.explain.MetricExplainService.ReportsMetricInputs(
                viewCurrency, family.getBaseCurrency(),
                firstNW, lastNW, firstLabel, lastLabel,
                slice.periodIds().size(), decomposition.size(),
                familyXirrDecimal, familyTwrDecimal,
                cumNetInflow, cumPnl,
                familyBenchmarkPct, bmInputs.size(), bmTotalBal,
                savAvail, savFilled, savTotal,
                savSumInc, savSumExp, savAvgInc, savAvgExp,
                savLatestInc, savLatestExp, savRateDec, savMedian);
        model.addAttribute("calc", metricExplain.reports(reportsInputs));
    }

    // v0.4 FR-60b · 砍 sankeyNodes / sankeyLinks(收入流向桑基图已删)

    /**
     * v0.4 FR-62b · AI 调仓建议视图(嵌入 model.rebalanceAdvice)。
     */
    public record RebalanceAdviceView(
        String narrative,
        java.util.List<java.util.Map<String, Object>> actions,
        java.time.LocalDateTime generatedAt
    ) {}

    /**
     * Reports 锚点 = 最新一期(无论 OPEN/CLOSED)。
     * <p>2026-05-10 与 dashboard 同步修复:旧逻辑优先取最新 CLOSED,会让用户在 OPEN 新月时
     * 看到的是上个月报表,与"实时汇总"产品定位冲突。
     */
    /**
     * 报表锚定期 · v0.5.5 FR-94 改:报表 = <b>已关账账期快照</b>。
     * 锚「最近已关账(≤今天)账期」;无则退 currentOpen / 最新一期仅渲染外壳(closedSnapshot=false)。
     * <p>v0.5.1 曾改 findCurrentOpen 优先(为绕未来测试期),代价是锚到月中半填的 OPEN 期 ——
     * 导致收益/人赚被进行中空账期拖成 0、XIRR/TWR 用半填净值失真。现回归快照语义,
     * 并用 {@code findLatestClosedAsOf(≤今天)} 干净挡掉未来期,不必再靠 OPEN 兜底。</p>
     */
    private ReportsAnchorResolver.AnchorChoice resolveAnchor(long familyId) {
        return ReportsAnchorResolver.resolve(
                periodMapper.findLatestClosedAsOf(familyId, LocalDate.now()),
                periodMapper.findCurrentOpen(familyId),
                periodMapper.findLatest(familyId, 1));
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

    private String percent(BigDecimal ratio) {
        if (ratio == null) {
            return "—";
        }
        return ratio.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
