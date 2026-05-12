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
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.FxMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.service.FamilyService;
import com.family.finance.service.FxService;
import com.family.finance.service.NavService;
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

    @GetMapping("/reports")
    public String reports(@AuthenticationPrincipal MemberPrincipal me,
                          @RequestParam(defaultValue = "1Y") String range,
                          @RequestParam(name = "accounts", required = false) List<Long> accounts,
                          @RequestParam(required = false) String currency,
                          @RequestHeader(value = "HX-Request", required = false) String htmx,
                          Model model) {
        String accountsCsv = accounts == null || accounts.isEmpty()
                ? null
                : accounts.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        populateModel(me, range, accountsCsv, currency, model);
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

    private void populateModel(MemberPrincipal me, String range, String accountsCsv, String currency, Model model) {
        Family family = familyService.require(me.getFamilyId());
        Period anchor = anchorPeriod(me.getFamilyId());
        List<Long> accountIds = parseAccountIds(accountsCsv);
        String viewCurrency = parseCurrency(currency, family.getBaseCurrency());
        // BUG-FIX(2026-05-11 · critical):非 base 账户币种 → 当期 fx_rate 必须存在,不然 SQL 走 1.0 兜底
        fxService.ensureForAccountCurrencies(me.getFamilyId(), family.getBaseCurrency(), anchor.getId());

        // BUG-FIX(2026-05-10):同 dashboard,缺 fx_rate 时即时拉 frankfurter,失败再回退 + toast 提示
        String requestedCurrency = viewCurrency;
        boolean fxFallback = false;
        if (!viewCurrency.equalsIgnoreCase(family.getBaseCurrency())) {
            boolean hasRate = fxService.getOrFetchRate(me.getFamilyId(), family.getBaseCurrency(), viewCurrency, anchor.getId()).isPresent();
            if (!hasRate) {
                viewCurrency = family.getBaseCurrency();
                fxFallback = true;
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
        List<WaterfallSegment> waterfall = factViewService.incomeExpenseWaterfall(slice);
        List<DecompositionPoint> decomposition = factViewService.principalVsReturnDecomposition(slice);
        List<TrendPoint> debtTrend = factViewService.debtTrend(slice);
        List<AccountPerformance> accountRows = factViewService.accountPerformance(slice);
        DecompositionPoint lastDecomposition = decomposition.isEmpty() ? null : decomposition.getLast();
        List<Account> allAccounts = accountMapper.findActiveByFamily(me.getFamilyId());
        List<FxRate> fxRates = fxMapper.findLatestByFamily(me.getFamilyId(), 36);

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

        model.addAttribute("familyXirr", percent(factViewService.familyXirr(slice)));
        model.addAttribute("familyTwr", percent(factViewService.familyTwr(slice)));
        model.addAttribute("cumulativeNetInflow", money(viewCurrency, lastDecomposition == null ? BigDecimal.ZERO : lastDecomposition.cumulativeNetInflow()));
        model.addAttribute("cumulativePnl", money(viewCurrency, lastDecomposition == null ? BigDecimal.ZERO : lastDecomposition.cumulativePnl()));
        model.addAttribute("waterfall", waterfall);
        model.addAttribute("decomposition", decomposition);
        model.addAttribute("debtTrend", debtTrend);
        model.addAttribute("accountRows", accountRows);
        model.addAttribute("fxRates", fxRates);
        model.addAttribute("fxFallback", fxFallback);
        model.addAttribute("requestedCurrency", requestedCurrency);
        model.addAttribute("sankeyNodes", sankeyNodes(slice));
        model.addAttribute("sankeyLinks", sankeyLinks(slice));

        model.addAttribute("labels", waterfall.stream().map(WaterfallSegment::label).toList());
        model.addAttribute("periodIds", waterfall.stream().map(WaterfallSegment::periodId).toList());
        model.addAttribute("incomeValues", waterfall.stream().map(WaterfallSegment::income).toList());
        model.addAttribute("expenseValues", waterfall.stream().map(WaterfallSegment::expense).toList());
        model.addAttribute("pnlValues", waterfall.stream().map(WaterfallSegment::pnl).toList());
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
            model.addAttribute("savingsLabels", savLabels);
            model.addAttribute("savingsIncome", savIncome);
            model.addAttribute("savingsExpense", savExpense);
            model.addAttribute("savingsMonthlyMedian", householdCashflowService.medianMonthlySavings(me.getFamilyId()));
            model.addAttribute("savingsRate", householdCashflowService.currentSavingsRate(me.getFamilyId()));
            model.addAttribute("avgMonthlyExpense", householdCashflowService.avgMonthlyExpense(me.getFamilyId()));
            model.addAttribute("avgMonthlyIncome", householdCashflowService.avgMonthlyIncome(me.getFamilyId()));
            model.addAttribute("savingsFilledMonths", ratio[0]);
            model.addAttribute("savingsTotalMonths", ratio[1]);
            model.addAttribute("savingsAvailable", ratio[0] > 0);
            model.addAttribute("goalsProgress", goalProgressService.computeAll(me.getFamilyId()));
        } catch (Exception e) {
            model.addAttribute("savingsAvailable", false);
            model.addAttribute("savingsFilledMonths", 0);
            model.addAttribute("savingsTotalMonths", 0);
            model.addAttribute("savingsLabels", List.of());
            model.addAttribute("savingsIncome", List.of());
            model.addAttribute("savingsExpense", List.of());
            model.addAttribute("goalsProgress", List.of());
        }
    }

    private List<Map<String, Object>> sankeyNodes(FactSlice slice) {
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        nodes.put("外部收入", Map.of("name", "外部收入"));
        slice.rows().stream()
                .filter(row -> row.incomeBase().signum() > 0)
                .forEach(row -> nodes.putIfAbsent(row.accountName(), Map.of("name", row.accountName())));
        return List.copyOf(nodes.values());
    }

    private List<Map<String, Object>> sankeyLinks(FactSlice slice) {
        return slice.rows().stream()
                .filter(row -> row.incomeBase().signum() > 0)
                .collect(java.util.stream.Collectors.groupingBy(AccountPeriodFact::accountName, LinkedHashMap::new,
                        java.util.stream.Collectors.reducing(BigDecimal.ZERO, AccountPeriodFact::incomeBase, BigDecimal::add)))
                .entrySet().stream()
                .map(entry -> Map.<String, Object>of("source", "外部收入", "target", entry.getKey(), "value", entry.getValue()))
                .toList();
    }

    /**
     * Reports 锚点 = 最新一期(无论 OPEN/CLOSED)。
     * <p>2026-05-10 与 dashboard 同步修复:旧逻辑优先取最新 CLOSED,会让用户在 OPEN 新月时
     * 看到的是上个月报表,与"实时汇总"产品定位冲突。
     */
    private Period anchorPeriod(long familyId) {
        return periodMapper.findLatest(familyId, 1).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("尚未创建周期"));
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
