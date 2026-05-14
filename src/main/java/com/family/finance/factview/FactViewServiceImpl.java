package com.family.finance.factview;

import com.family.finance.calc.TwrCalculator;
import com.family.finance.calc.XirrCalculator;
import com.family.finance.domain.account.AccountClass;
import com.family.finance.domain.account.AccountLiquidity;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.period.PeriodType;
import com.family.finance.repository.FactMapper;
import com.family.finance.repository.FamilyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FactViewServiceImpl implements FactViewService {
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final FactMapper factMapper;
    private final FamilyMapper familyMapper;
    /** v0.4.3 B2 修复 · 月均支出/收入统一源 · PMC(成员级)优先 · cash_flow fallback */
    private final com.family.finance.repository.PeriodMemberCashflowMapper periodMemberCashflowMapper;

    @Override
    public FactSlice loadDefault(Long familyId) {
        Family family = familyMapper.findById(familyId)
                .orElseThrow(() -> new IllegalArgumentException("家庭不存在: " + familyId));
        LocalDate end = LocalDate.now().withDayOfMonth(1);
        LocalDate start = end.minusMonths(11);
        return load(new FactFilter(familyId, family.getPeriodType(), start, end, false, null, family.getBaseCurrency()));
    }

    @Override
    public FactSlice load(FactFilter filter) {
        List<AccountPeriodFact> rows = factMapper.queryBase(filter).stream()
                .map(FactProjector::project)
                .toList();
        Map<Long, LocalDate> periodStartById = rows.stream()
                .collect(Collectors.toMap(AccountPeriodFact::periodId, AccountPeriodFact::periodStart, (a, b) -> a));
        List<Long> periodIds = periodStartById.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .toList();
        Long lastPeriodId = periodIds.isEmpty() ? null : periodIds.getLast();
        return new FactSlice(filter, rows, periodIds, lastPeriodId);
    }

    @Override
    public KpiSnapshot kpis(FactSlice slice) {
        if (slice.lastPeriodId() == null) {
            return new KpiSnapshot(zero(), zero(), zero(), null, null, null, null);
        }
        Long last = slice.lastPeriodId();
        Long previous = previousPeriodId(slice, last);
        BigDecimal netWorth = netWorth(slice, last);
        BigDecimal previousNetWorth = previous == null ? null : netWorth(slice, previous);
        BigDecimal totalAssets = sumEnd(slice, last, row -> row.accountClass() == AccountClass.ASSET);
        BigDecimal totalLiabilities = slice.rows().stream()
                .filter(row -> Objects.equals(row.periodId(), last))
                .filter(row -> row.accountClass() == AccountClass.LIABILITY)
                .map(AccountPeriodFact::endBalanceBase)
                .filter(Objects::nonNull)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_EVEN);
        // PRD § 5.11 流动资产 = LIQUID(仅 CASH);WEALTH 是 SEMI_LIQUID 不计入紧急储备月数
        BigDecimal liquidAssets = sumEnd(slice, last,
                row -> row.accountLiquidity() == AccountLiquidity.LIQUID);
        BigDecimal avgExpense = averageExpense(slice, 12);
        BigDecimal emergencyMonths = avgExpense.signum() == 0
                ? null
                : liquidAssets.divide(avgExpense, 1, RoundingMode.HALF_EVEN);
        BigDecimal debtRatio = totalAssets.signum() == 0
                ? null
                : totalLiabilities.divide(totalAssets, 6, RoundingMode.HALF_EVEN);
        BigDecimal delta = previousNetWorth == null ? null : netWorth.subtract(previousNetWorth).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal deltaPct = previousNetWorth == null || previousNetWorth.signum() == 0
                ? null
                : delta.divide(previousNetWorth, 6, RoundingMode.HALF_EVEN);

        // v0.4.2 · "资产年化"二分(剔除外部现金流的纯投资视角)
        BigDecimal monthlyPnlAmount = null;
        BigDecimal monthlyInvestReturnPct = null;
        if (previousNetWorth != null && previousNetWorth.signum() > 0) {
            BigDecimal lastNetInflow = netInflowForPeriod(slice, last);
            var monthly = com.family.finance.calc.InvestmentReturnCalculator.monthly(
                previousNetWorth, netWorth, lastNetInflow);
            monthlyPnlAmount = monthly.pnlAmount();
            monthlyInvestReturnPct = monthly.pnlPct();
        }
        // 12 月年化 = 已有 familyTwr(slice 默认 1Y · 即 12 月窗口)· 直接 alias 共享算法
        BigDecimal annualizedInvestReturnPct = familyTwr(slice);
        // 本年(自然年)累计纯投资 PnL
        BigDecimal ytdInvestPnl = ytdInvestPnl(slice);

        return new KpiSnapshot(netWorth, totalAssets, totalLiabilities, emergencyMonths, debtRatio, delta, deltaPct,
            monthlyPnlAmount, monthlyInvestReturnPct, annualizedInvestReturnPct, ytdInvestPnl);
    }

    /** v0.4.2 助手:某 period 的净流入(income − expense + transfer 净)汇总到家庭本位币 */
    private BigDecimal netInflowForPeriod(FactSlice slice, Long periodId) {
        BigDecimal net = BigDecimal.ZERO;
        for (AccountPeriodFact r : slice.rows()) {
            if (!Objects.equals(r.periodId(), periodId)) continue;
            BigDecimal inc = r.incomeBase() == null ? BigDecimal.ZERO : r.incomeBase();
            BigDecimal exp = r.expenseBase() == null ? BigDecimal.ZERO : r.expenseBase();
            BigDecimal tIn = r.transferInBase() == null ? BigDecimal.ZERO : r.transferInBase();
            BigDecimal tOut = r.transferOutBase() == null ? BigDecimal.ZERO : r.transferOutBase();
            // 跨账户 transfer 在家庭层面自然抵消(in 和 out 同金额不同账户)· 这里全加在一起累计每个账户的净
            net = net.add(inc).subtract(exp).add(tIn).subtract(tOut);
        }
        return net.setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * v0.4.2 助手:本年(自然年)累计纯投资 PnL。
     *
     * <p>v0.4.3 B4 修复:**独立加载** Jan1-now 数据 · 不再依赖 caller slice 的 range
     * (避免 range=3M 时 YTD 只见 3 月的 bug)· 多加载 1 期获取期初 NetWorth。</p>
     */
    private BigDecimal ytdInvestPnl(FactSlice slice) {
        long familyId = slice.filter().familyId();
        java.time.LocalDate now = java.time.LocalDate.now();
        // 多回退 1 个月 · 拿到去年 12 月的 snapshot 作期初
        java.time.LocalDate ytdStart = java.time.LocalDate.of(now.getYear(), 1, 1).minusMonths(1);
        FactSlice ytdSlice;
        try {
            ytdSlice = load(new FactFilter(
                familyId,
                slice.filter().periodType(),
                ytdStart,
                now.withDayOfMonth(1),
                false,
                null,
                slice.filter().viewCurrency()
            ));
        } catch (Exception e) {
            return null;
        }
        int currentYear = now.getYear();
        List<com.family.finance.calc.TwrCalculator.TwrPoint> ytdPoints = new ArrayList<>();
        for (Long periodId : ytdSlice.periodIds()) {
            java.time.LocalDate pStart = periodStart(ytdSlice, periodId);
            if (pStart == null || pStart.getYear() != currentYear) continue;
            Long prev = previousPeriodId(ytdSlice, periodId);
            BigDecimal start = prev == null ? null : netWorth(ytdSlice, prev);
            BigDecimal end = netWorth(ytdSlice, periodId);
            BigDecimal inflow = netInflowForPeriod(ytdSlice, periodId);
            if (start != null && end != null && start.signum() > 0) {
                ytdPoints.add(new com.family.finance.calc.TwrCalculator.TwrPoint(start, end, inflow));
            }
        }
        return com.family.finance.calc.InvestmentReturnCalculator.ytdPnlAmount(ytdPoints);
    }

    @Override
    public List<TrendPoint> netWorthTrend(FactSlice slice) {
        return slice.periodIds().stream()
                .map(periodId -> new TrendPoint(periodId, periodStart(slice, periodId), label(slice, periodId), netWorth(slice, periodId)))
                .toList();
    }

    @Override
    public List<AllocationSlice> allocationByType(FactSlice slice, Long periodId) {
        if (periodId == null) {
            return List.of();
        }
        Map<AccountType, BigDecimal> byType = slice.rows().stream()
                .filter(row -> Objects.equals(row.periodId(), periodId))
                .filter(row -> row.accountClass() == AccountClass.ASSET)
                .filter(row -> row.endBalanceBase() != null)
                .collect(Collectors.groupingBy(AccountPeriodFact::accountType, LinkedHashMap::new,
                        Collectors.reducing(BigDecimal.ZERO, AccountPeriodFact::endBalanceBase, BigDecimal::add)));
        BigDecimal total = byType.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return byType.entrySet().stream()
                .map(entry -> new AllocationSlice(
                        entry.getKey().name(),
                        entry.getKey().getLabel() + "\n(" + entry.getKey().name() + ")",
                        entry.getValue().setScale(2, RoundingMode.HALF_EVEN),
                        total.signum() == 0 ? BigDecimal.ZERO : entry.getValue().divide(total, 6, RoundingMode.HALF_EVEN)))
                .toList();
    }

    @Override
    public List<WaterfallSegment> incomeExpenseWaterfall(FactSlice slice) {
        List<WaterfallSegment> result = new ArrayList<>();
        for (Long periodId : slice.periodIds()) {
            Long previous = previousPeriodId(slice, periodId);
            result.add(new WaterfallSegment(
                    periodId,
                    periodStart(slice, periodId),
                    label(slice, periodId),
                    previous == null ? BigDecimal.ZERO.setScale(2) : netWorth(slice, previous),
                    periodIncome(slice, periodId),
                    periodExpense(slice, periodId),
                    periodPnl(slice, periodId),
                    netWorth(slice, periodId)
            ));
        }
        return result;
    }

    @Override
    public BigDecimal savingsRate(FactSlice slice) {
        if (slice.lastPeriodId() == null) {
            return null;
        }
        BigDecimal income = periodIncome(slice, slice.lastPeriodId());
        if (income.signum() == 0) {
            return null;
        }
        return income.subtract(periodExpense(slice, slice.lastPeriodId()))
                .divide(income, 6, RoundingMode.HALF_EVEN);
    }

    @Override
    public Map<Long, BigDecimal> accountXirr(FactSlice slice) {
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        for (Map.Entry<Long, List<AccountPeriodFact>> entry : slice.byAccount().entrySet()) {
            List<AccountPeriodFact> rows = entry.getValue().stream()
                    .filter(row -> row.endBalanceOrig() != null)
                    .sorted(Comparator.comparing(AccountPeriodFact::periodStart))
                    .toList();
            result.put(entry.getKey(), xirrForAccountRows(rows));
        }
        return result;
    }

    @Override
    public BigDecimal familyXirr(FactSlice slice) {
        if (slice.periodIds().size() < 2) {
            return null;
        }
        List<XirrCalculator.CashFlowPoint> flows = new ArrayList<>();
        Long first = slice.periodIds().getFirst();
        Long last = slice.periodIds().getLast();
        flows.add(new XirrCalculator.CashFlowPoint(periodEnd(slice, first), netWorth(slice, first).negate()));
        for (int i = 1; i < slice.periodIds().size(); i++) {
            Long periodId = slice.periodIds().get(i);
            BigDecimal external = periodIncome(slice, periodId).subtract(periodExpense(slice, periodId));
            if (external.signum() != 0) {
                flows.add(new XirrCalculator.CashFlowPoint(periodEnd(slice, periodId), external.negate()));
            }
        }
        flows.add(new XirrCalculator.CashFlowPoint(periodEnd(slice, last), netWorth(slice, last)));
        return XirrCalculator.annualizedOrCumulative(flows, slice.periodIds().size());
    }

    @Override
    public BigDecimal familyTwr(FactSlice slice) {
        if (slice.periodIds().size() < 2) {
            return null;
        }
        List<TwrCalculator.TwrPoint> points = new ArrayList<>();
        for (int i = 1; i < slice.periodIds().size(); i++) {
            Long previous = slice.periodIds().get(i - 1);
            Long current = slice.periodIds().get(i);
            points.add(new TwrCalculator.TwrPoint(
                    netWorth(slice, previous),
                    netWorth(slice, current),
                    periodIncome(slice, current).subtract(periodExpense(slice, current))
            ));
        }
        return TwrCalculator.annualizedOrCumulative(points, points.size());
    }

    @Override
    public List<DecompositionPoint> principalVsReturnDecomposition(FactSlice slice) {
        List<DecompositionPoint> result = new ArrayList<>();
        BigDecimal cumulativeExternal = BigDecimal.ZERO;
        BigDecimal cumulativePnl = BigDecimal.ZERO;
        for (int i = 1; i < slice.periodIds().size(); i++) {
            Long periodId = slice.periodIds().get(i);
            cumulativeExternal = cumulativeExternal.add(periodIncome(slice, periodId)).subtract(periodExpense(slice, periodId));
            cumulativePnl = cumulativePnl.add(periodPnl(slice, periodId));
            result.add(new DecompositionPoint(
                    periodId,
                    periodStart(slice, periodId),
                    label(slice, periodId),
                    cumulativeExternal.setScale(2, RoundingMode.HALF_EVEN),
                    cumulativePnl.setScale(2, RoundingMode.HALF_EVEN)
            ));
        }
        return result;
    }

    @Override
    public List<TrendPoint> debtTrend(FactSlice slice) {
        return slice.periodIds().stream()
                .map(periodId -> new TrendPoint(periodId, periodStart(slice, periodId), label(slice, periodId),
                        slice.rows().stream()
                                .filter(row -> Objects.equals(row.periodId(), periodId))
                                .filter(row -> row.accountClass() == AccountClass.LIABILITY)
                                .map(AccountPeriodFact::endBalanceBase)
                                .filter(Objects::nonNull)
                                .map(BigDecimal::abs)
                                .reduce(BigDecimal.ZERO, BigDecimal::add)
                                .setScale(2, RoundingMode.HALF_EVEN)))
                .toList();
    }

    @Override
    public List<AccountPerformance> accountPerformance(FactSlice slice) {
        Map<Long, BigDecimal> xirr = accountXirr(slice);
        return slice.byAccount().values().stream()
                .map(rows -> rows.stream().sorted(Comparator.comparing(AccountPeriodFact::periodStart)).toList())
                .map(rows -> {
                    AccountPeriodFact first = rows.getFirst();
                    AccountPeriodFact latest = rows.stream()
                            .filter(row -> row.endBalanceBase() != null)
                            .reduce((a, b) -> b)
                            .orElse(first);
                    List<TrendPoint> spark = rows.stream()
                            .filter(row -> row.endBalanceBase() != null)
                            .map(row -> new TrendPoint(row.periodId(), row.periodStart(), label(row.periodStart()), row.endBalanceBase()))
                            .toList();
                    return new AccountPerformance(
                            first.accountId(),
                            first.accountName(),
                            first.accountType(),
                            first.accountCurrency(),
                            latest.endBalanceBase(),
                            xirr.get(first.accountId()),
                            spark
                    );
                })
                .sorted(Comparator.comparing(AccountPerformance::accountId))
                .toList();
    }

    private BigDecimal xirrForAccountRows(List<AccountPeriodFact> rows) {
        if (rows.size() < 2) {
            return null;
        }
        List<XirrCalculator.CashFlowPoint> flows = new ArrayList<>();
        AccountPeriodFact first = rows.getFirst();
        AccountPeriodFact last = rows.getLast();
        flows.add(new XirrCalculator.CashFlowPoint(first.periodEnd(), first.endBalanceOrig().negate()));
        for (int i = 1; i < rows.size(); i++) {
            AccountPeriodFact row = rows.get(i);
            BigDecimal netExternal = row.incomeOrig()
                    .subtract(row.expenseOrig())
                    .add(row.transferInOrig())
                    .subtract(row.transferOutOrig());
            if (netExternal.signum() != 0) {
                flows.add(new XirrCalculator.CashFlowPoint(row.periodEnd(), netExternal.negate()));
            }
        }
        flows.add(new XirrCalculator.CashFlowPoint(last.periodEnd(), last.endBalanceOrig()));
        return XirrCalculator.annualizedOrCumulative(flows, rows.size());
    }

    private Long previousPeriodId(FactSlice slice, Long periodId) {
        int index = slice.periodIds().indexOf(periodId);
        return index <= 0 ? null : slice.periodIds().get(index - 1);
    }

    private BigDecimal netWorth(FactSlice slice, Long periodId) {
        return sumEnd(slice, periodId, row -> true);
    }

    private BigDecimal sumEnd(FactSlice slice, Long periodId, Predicate<AccountPeriodFact> predicate) {
        return slice.rows().stream()
                .filter(row -> Objects.equals(row.periodId(), periodId))
                .filter(predicate)
                .map(AccountPeriodFact::endBalanceBase)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_EVEN);
    }

    private BigDecimal periodIncome(FactSlice slice, Long periodId) {
        return sumMeasure(slice, periodId, AccountPeriodFact::incomeBase);
    }

    private BigDecimal periodExpense(FactSlice slice, Long periodId) {
        return sumMeasure(slice, periodId, AccountPeriodFact::expenseBase);
    }

    private BigDecimal periodPnl(FactSlice slice, Long periodId) {
        return slice.rows().stream()
                .filter(row -> Objects.equals(row.periodId(), periodId))
                .map(AccountPeriodFact::periodPnlBase)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_EVEN);
    }

    private BigDecimal sumMeasure(FactSlice slice, Long periodId, java.util.function.Function<AccountPeriodFact, BigDecimal> mapper) {
        return slice.rows().stream()
                .filter(row -> Objects.equals(row.periodId(), periodId))
                .map(mapper)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * v0.4.3 B2 · 月均支出统一源 · PMC 优先 · cash_flow fallback。
     *
     * <p>v0.3 引入 period_member_cashflow.total_expense_input(用户在 /entry 第一步填家庭口径) ·
     * 比 cash_flow 表 by-account 加和更准(用户可能只填家庭总额没逐笔)。</p>
     *
     * <p>backward compat:PMC 空 → fallback 老 cash_flow 加和路径。</p>
     */
    private BigDecimal averageExpense(FactSlice slice, int maxPeriods) {
        // 1) PMC 优先(v0.3 用户填家庭口径)
        long familyId = slice.filter().familyId();
        var recent = periodMemberCashflowMapper.findFamilyAggregateRecent(familyId, maxPeriods);
        if (!recent.isEmpty()) {
            BigDecimal sum = BigDecimal.ZERO;
            for (var a : recent) {
                if (a.totalExpense() != null) sum = sum.add(a.totalExpense());
            }
            return sum.divide(BigDecimal.valueOf(recent.size()), 2, RoundingMode.HALF_EVEN);
        }
        // 2) Fallback · v0.2 cash_flow 表加和
        List<Long> ids = slice.periodIds();
        if (ids.isEmpty()) {
            return zero();
        }
        int from = Math.max(0, ids.size() - maxPeriods);
        List<Long> window = ids.subList(from, ids.size());
        BigDecimal total = window.stream()
                .map(periodId -> periodExpense(slice, periodId))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(BigDecimal.valueOf(window.size()), 2, RoundingMode.HALF_EVEN);
    }

    private LocalDate periodStart(FactSlice slice, Long periodId) {
        return slice.rows().stream()
                .filter(row -> Objects.equals(row.periodId(), periodId))
                .findFirst()
                .map(AccountPeriodFact::periodStart)
                .orElse(slice.filter().rangeStart());
    }

    private LocalDate periodEnd(FactSlice slice, Long periodId) {
        return slice.rows().stream()
                .filter(row -> Objects.equals(row.periodId(), periodId))
                .findFirst()
                .map(AccountPeriodFact::periodEnd)
                .orElse(slice.filter().rangeEnd());
    }

    private String label(FactSlice slice, Long periodId) {
        return label(periodStart(slice, periodId));
    }

    private String label(LocalDate periodStart) {
        return periodStart == null ? "" : MONTH_LABEL.format(periodStart);
    }

    private BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
    }
}
