package com.family.finance.service;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.category.ProductCategory;
import com.family.finance.domain.flow.CashFlow;
import com.family.finance.domain.flow.CashFlowKind;
import com.family.finance.domain.member.Member;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodStatus;
import com.family.finance.domain.snapshot.PeriodSnapshot;
import com.family.finance.domain.transfer.Transfer;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.CashFlowMapper;
import com.family.finance.repository.MemberMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.SnapshotMapper;
import com.family.finance.repository.TransferMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * v0.2 FR-30 · 账户详情页(账本视角)聚合服务
 *
 * <p>"跨期完整流水档案":在一个 service 调用里把 snapshot / cashflow / transfer
 * 合并成统一的 ledger 视图,按月分组。
 */
@Service
@RequiredArgsConstructor
public class AccountDetailService {

    private final AccountMapper accountMapper;
    private final MemberMapper memberMapper;
    private final PeriodMapper periodMapper;
    private final SnapshotMapper snapshotMapper;
    private final CashFlowMapper cashFlowMapper;
    private final TransferMapper transferMapper;
    /** v0.4.1 FR-52f · 股票估值事件 */
    private final com.family.finance.repository.StockValuationEventMapper stockValuationEventMapper;
    private final ProductCategoryService productCategoryService;

    /**
     * @param familyId 家庭 id
     * @param accountId 账户 id
     * @param filterType  null / "ALL" / "INCOME" / "EXPENSE" / "TRANSFER" / "SNAPSHOT"
     * @param rangeMonths null / 1 / 3 / 6 / 12 / 24 / 0(全部)— 默认 12
     * @param keyword     备注关键字,可空
     */
    public AccountDetail detail(long familyId, long accountId,
                                String filterType, Integer rangeMonths, String keyword) {
        Account account = accountMapper.findById(accountId)
                .filter(a -> a.getFamilyId() == familyId)
                .orElseThrow(() -> new IllegalArgumentException("账户不存在或不属于本家庭: " + accountId));

        Member owner = account.getPrimaryOwnerMemberId() == null
                ? null
                : memberMapper.findActiveByFamily(familyId).stream()
                        .filter(m -> m.getId().equals(account.getPrimaryOwnerMemberId()))
                        .findFirst().orElse(null);

        ProductCategory category = account.getProductCategoryCode() == null
                ? null
                : productCategoryService.findByCode(account.getProductCategoryCode()).orElse(null);

        Map<Long, Account> allById = accountMapper.findAllByFamily(familyId).stream()
                .collect(java.util.stream.Collectors.toMap(Account::getId, java.util.function.Function.identity()));
        List<Period> periods = periodMapper.findAllByFamily(familyId);
        Map<Long, Period> periodById = periods.stream()
                .collect(java.util.stream.Collectors.toMap(Period::getId, java.util.function.Function.identity()));

        // 1. 余额时序(全期 snapshot,按 period_start 排序)
        List<PeriodSnapshot> snapshots = snapshotMapper.findAllByFamily(familyId).stream()
                .filter(s -> Objects.equals(s.getAccountId(), accountId))
                .toList();
        Map<Long, PeriodSnapshot> snapByPeriod = snapshots.stream()
                .collect(java.util.stream.Collectors.toMap(PeriodSnapshot::getPeriodId, java.util.function.Function.identity()));

        List<AccountDetail.TrendPoint> trend = new ArrayList<>();
        periods.stream()
                .sorted(Comparator.comparing(Period::getPeriodStart))
                .forEach(p -> {
                    PeriodSnapshot s = snapByPeriod.get(p.getId());
                    if (s != null) {
                        trend.add(new AccountDetail.TrendPoint(p.getPeriodStart(), s.getEndBalance()));
                    }
                });

        BigDecimal currentBalance = trend.isEmpty() ? null : trend.get(trend.size() - 1).endBalance();
        BigDecimal previousBalance = trend.size() < 2 ? null : trend.get(trend.size() - 2).endBalance();
        BigDecimal monthDelta = (currentBalance != null && previousBalance != null)
                ? currentBalance.subtract(previousBalance) : null;

        // 2. 拉本账户全部流水 + 拉转账(双向)
        List<CashFlow> allCashFlows = cashFlowMapper.findAllByFamily(familyId).stream()
                .filter(cf -> Objects.equals(cf.getAccountId(), accountId))
                .toList();
        List<Transfer> allTransfers = transferMapper.findAllByFamily(familyId).stream()
                .filter(t -> Objects.equals(t.getFromAccountId(), accountId)
                        || Objects.equals(t.getToAccountId(), accountId))
                .toList();

        // 3. 累计 KPI
        BigDecimal cumIncome = allCashFlows.stream()
                .filter(cf -> cf.getKind() == CashFlowKind.INCOME)
                .map(CashFlow::getAmount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cumExpense = allCashFlows.stream()
                .filter(cf -> cf.getKind() == CashFlowKind.EXPENSE)
                .map(CashFlow::getAmount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cumTransferIn = allTransfers.stream()
                .filter(t -> Objects.equals(t.getToAccountId(), accountId))
                .map(Transfer::getAmount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cumTransferOut = allTransfers.stream()
                .filter(t -> Objects.equals(t.getFromAccountId(), accountId))
                .map(Transfer::getAmount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cumulativeNetInflow = cumIncome.subtract(cumExpense)
                .add(cumTransferIn).subtract(cumTransferOut)
                .setScale(2, RoundingMode.HALF_EVEN);

        int monthCount = Math.max(1, snapshots.size());
        BigDecimal avgIncome = cumIncome.add(cumTransferIn)
                .divide(BigDecimal.valueOf(monthCount), 2, RoundingMode.HALF_EVEN);
        BigDecimal avgExpense = cumExpense.add(cumTransferOut)
                .divide(BigDecimal.valueOf(monthCount), 2, RoundingMode.HALF_EVEN);

        int totalEntries = allCashFlows.size() + allTransfers.size() + snapshots.size();

        // 4. 把所有流水合并成 Entry,按 period 分组,然后过滤
        Map<LocalDate, List<AccountDetail.Entry>> byMonth = new TreeMap<>(Comparator.reverseOrder());
        Map<LocalDate, Period> periodByStart = new HashMap<>();
        periods.forEach(p -> periodByStart.put(p.getPeriodStart(), p));

        // SNAPSHOT entries
        for (PeriodSnapshot s : snapshots) {
            Period p = periodById.get(s.getPeriodId());
            if (p == null) continue;
            byMonth.computeIfAbsent(p.getPeriodStart(), k -> new ArrayList<>()).add(new AccountDetail.Entry(
                    AccountDetail.Kind.SNAPSHOT,
                    s.getSubmittedAt(),
                    s.getEndBalance(),
                    "= " + MoneyFormat.format(account.getCurrency(), s.getEndBalance()),
                    "月末校准",
                    s.getNote(),
                    null,
                    false
            ));
        }
        // CASH_FLOW entries
        for (CashFlow cf : allCashFlows) {
            Period p = periodById.get(cf.getPeriodId());
            if (p == null) continue;
            boolean income = cf.getKind() == CashFlowKind.INCOME;
            String sign = income ? "+" : "−";
            byMonth.computeIfAbsent(p.getPeriodStart(), k -> new ArrayList<>()).add(new AccountDetail.Entry(
                    income ? AccountDetail.Kind.INCOME : AccountDetail.Kind.EXPENSE,
                    cf.getSubmittedAt(),
                    cf.getAmount(),
                    sign + MoneyFormat.format(account.getCurrency(), cf.getAmount()),
                    cf.getCategoryCode(),
                    cf.getNote(),
                    cf.getId(),
                    p.getStatus() == PeriodStatus.OPEN
            ));
        }
        // v0.4.1 FR-52f · STOCK 估值事件 entries
        if (account.getType() == com.family.finance.domain.account.AccountType.STOCK) {
            try {
                for (com.family.finance.domain.stock.StockValuationEvent ev :
                        stockValuationEventMapper.findRecentByAccount(accountId, 500)) {
                    Period p = periodById.get(ev.getPeriodId());
                    if (p == null) continue;
                    String sign = ev.getDelta().signum() >= 0 ? "+" : "−";
                    String trgLabel = switch (ev.getTriggerKind() == null ? "" : ev.getTriggerKind()) {
                        case "CRON" -> "自动(定时)";
                        case "MANUAL" -> "手动刷价";
                        case "HOLDING_CHANGE" -> "持仓变动";
                        default -> "自动";
                    };
                    String note = ev.getNote() != null ? ev.getNote()
                        : (ev.getPrevBalance() != null
                            ? "从 " + MoneyFormat.format(account.getCurrency(), ev.getPrevBalance())
                              + " → " + MoneyFormat.format(account.getCurrency(), ev.getNewBalance())
                            : null);
                    byMonth.computeIfAbsent(p.getPeriodStart(), k -> new ArrayList<>()).add(new AccountDetail.Entry(
                        AccountDetail.Kind.VALUATION,
                        ev.getTriggeredAt(),
                        ev.getDelta().abs(),
                        sign + MoneyFormat.format(account.getCurrency(), ev.getDelta().abs()),
                        "估值变动 · " + trgLabel,
                        note,
                        ev.getId(),
                        false  // 估值事件不可删
                    ));
                }
            } catch (Exception ignored) {
                // ledger 渲染失败不阻塞整体页面
            }
        }
        // TRANSFER entries(本账户视角:in / out 二选一)
        for (Transfer t : allTransfers) {
            Period p = periodById.get(t.getPeriodId());
            if (p == null) continue;
            boolean in = Objects.equals(t.getToAccountId(), accountId);
            Account counter = allById.get(in ? t.getFromAccountId() : t.getToAccountId());
            String counterName = counter == null ? "其他账户" : counter.getDisplayName();
            byMonth.computeIfAbsent(p.getPeriodStart(), k -> new ArrayList<>()).add(new AccountDetail.Entry(
                    in ? AccountDetail.Kind.TRANSFER_IN : AccountDetail.Kind.TRANSFER_OUT,
                    t.getSubmittedAt(),
                    t.getAmount(),
                    (in ? "+" : "−") + MoneyFormat.format(account.getCurrency(), t.getAmount()),
                    (in ? "↳ 来自 " : "↱ 划出到 ") + counterName,
                    t.getNote(),
                    t.getId(),
                    p.getStatus() == PeriodStatus.OPEN
            ));
        }

        // 5. 应用筛选
        AccountDetail.Kind kindFilter = parseKindFilter(filterType);
        int range = rangeMonths == null ? 12 : Math.max(0, rangeMonths);
        LocalDate cutoff = range == 0 ? null : LocalDate.now().minusMonths(range).withDayOfMonth(1);
        String kw = keyword == null ? null : keyword.trim().toLowerCase();
        if (kw != null && kw.isEmpty()) kw = null;

        List<AccountDetail.MonthGroup> result = new ArrayList<>();
        for (Map.Entry<LocalDate, List<AccountDetail.Entry>> me : byMonth.entrySet()) {
            LocalDate periodStart = me.getKey();
            if (cutoff != null && periodStart.isBefore(cutoff)) continue;

            String finalKw = kw;
            List<AccountDetail.Entry> filtered = me.getValue().stream()
                    .filter(e -> kindFilter == null || matchesKind(e.kind(), kindFilter))
                    .filter(e -> finalKw == null
                            || (e.label() != null && e.label().toLowerCase().contains(finalKw))
                            || (e.note() != null && e.note().toLowerCase().contains(finalKw)))
                    .sorted(Comparator.comparing(AccountDetail.Entry::occurredAt,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
            if (filtered.isEmpty()) continue;

            BigDecimal net = filtered.stream()
                    .filter(e -> e.kind() != AccountDetail.Kind.SNAPSHOT)
                    .map(e -> {
                        boolean positive = e.kind() == AccountDetail.Kind.INCOME
                                || e.kind() == AccountDetail.Kind.TRANSFER_IN;
                        BigDecimal v = Optional.ofNullable(e.amount()).orElse(BigDecimal.ZERO);
                        return positive ? v : v.negate();
                    })
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_EVEN);

            Period p = periodByStart.get(periodStart);
            boolean open = p != null && p.getStatus() == PeriodStatus.OPEN;
            result.add(new AccountDetail.MonthGroup(
                    periodStart,
                    filtered.size(),
                    net,
                    (net.signum() >= 0 ? "+" : "") + MoneyFormat.format(account.getCurrency(), net),
                    open,
                    filtered
            ));
        }

        return new AccountDetail(
                account,
                owner,
                category,
                currentBalance,
                previousBalance,
                monthDelta,
                cumulativeNetInflow,
                avgIncome,
                avgExpense,
                totalEntries,
                trend,
                result
        );
    }

    private static AccountDetail.Kind parseKindFilter(String s) {
        if (s == null || s.isBlank() || "ALL".equalsIgnoreCase(s)) return null;
        return switch (s.toUpperCase()) {
            case "INCOME" -> AccountDetail.Kind.INCOME;
            case "EXPENSE" -> AccountDetail.Kind.EXPENSE;
            case "TRANSFER" -> AccountDetail.Kind.TRANSFER_IN; // 用 IN 当哨兵,matchesKind 内部展开 IN/OUT
            case "SNAPSHOT" -> AccountDetail.Kind.SNAPSHOT;
            default -> null;
        };
    }

    private static boolean matchesKind(AccountDetail.Kind actual, AccountDetail.Kind filter) {
        if (filter == AccountDetail.Kind.TRANSFER_IN) {
            return actual == AccountDetail.Kind.TRANSFER_IN || actual == AccountDetail.Kind.TRANSFER_OUT;
        }
        return actual == filter;
    }
}
