package com.family.finance.service;

import com.family.finance.calc.ReconciliationCalculator;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.domain.flow.CashFlow;
import com.family.finance.domain.flow.CashFlowKind;
import com.family.finance.domain.member.Member;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodStatus;
import com.family.finance.domain.snapshot.PeriodSnapshot;
import com.family.finance.domain.snapshot.SnapshotTodo;
import com.family.finance.domain.snapshot.TodoStatus;
import com.family.finance.domain.stock.StockValuationEvent;
import com.family.finance.domain.transfer.Transfer;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.CashFlowMapper;
import com.family.finance.repository.MemberMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.SnapshotMapper;
import com.family.finance.repository.SnapshotTodoMapper;
import com.family.finance.repository.StockValuationEventMapper;
import com.family.finance.repository.TransferMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EntryService {

    private final AccountMapper accountMapper;
    private final MemberMapper memberMapper;
    private final PeriodMapper periodMapper;
    private final SnapshotMapper snapshotMapper;
    private final SnapshotTodoMapper snapshotTodoMapper;
    private final CashFlowMapper cashFlowMapper;
    private final TransferMapper transferMapper;
    private final AuditLogService auditLogService;
    /** v0.4.1 FR-52f · 股票估值事件 · ledger 显示 */
    private final StockValuationEventMapper stockValuationEventMapper;

    public Optional<Period> findSelectedPeriod(long familyId, String periodParam) {
        if (periodParam == null || periodParam.isBlank()) {
            return periodMapper.findCurrentOpen(familyId)
                    .or(() -> periodMapper.findLatest(familyId, 1).stream().findFirst());
        }
        if (periodParam.matches("\\d+")) {
            return periodMapper.findById(Long.parseLong(periodParam));
        }
        if (periodParam.matches("\\d{4}-\\d{2}")) {
            int year = Integer.parseInt(periodParam.substring(0, 4));
            int month = Integer.parseInt(periodParam.substring(5, 7));
            return periodMapper.findLatest(familyId, 36).stream()
                    .filter(p -> p.getPeriodStart().getYear() == year && p.getPeriodStart().getMonthValue() == month)
                    .findFirst();
        }
        return Optional.empty();
    }

    /** Thymeleaf SpEL 调用绕过 record accessor 在某些 fragment 嵌套场景下返回 null 的问题。 */
    public static List<EntryRow.LedgerEntry> safeLedger(EntryRow row) {
        return row == null || row.ledger() == null ? List.of() : row.ledger();
    }

    /** 触发源中文化 · 给 ledger 显示用 */
    private static String triggerCn(String triggerKind) {
        if (triggerKind == null) return "自动";
        return switch (triggerKind) {
            case "CRON" -> "自动(定时)";
            case "MANUAL" -> "手动刷价";
            case "HOLDING_CHANGE" -> "持仓变动";
            default -> "自动";
        };
    }

    public List<EntryRow> listRows(long familyId, long memberId, Period period, boolean mineOnly) {
        List<Account> accounts = accountMapper.findActiveByFamily(familyId).stream()
                .filter(a -> !mineOnly
                        || a.getPrimaryOwnerMemberId() == null
                        || a.getPrimaryOwnerMemberId() == memberId)
                .toList();
        Map<Long, Account> allById = accountMapper.findAllByFamily(familyId).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        Map<Long, Member> members = memberMapper.findActiveByFamily(familyId).stream()
                .collect(Collectors.toMap(Member::getId, Function.identity()));
        Map<Long, PeriodSnapshot> current = snapshotMapper.findByPeriod(period.getId()).stream()
                .collect(Collectors.toMap(PeriodSnapshot::getAccountId, Function.identity()));
        Map<Long, SnapshotTodo> todos = snapshotTodoMapper.findByPeriod(period.getId()).stream()
                .collect(Collectors.toMap(SnapshotTodo::getAccountId, Function.identity()));

        return accounts.stream()
                .map(account -> toRow(account, members, allById, current.get(account.getId()), todos.get(account.getId()), period))
                .sorted(Comparator.comparingInt(r -> Optional.ofNullable(r.account().getDisplayOrder()).orElse(0)))
                .toList();
    }

    public EntryRow rowFor(long familyId, long memberId, long periodId, long accountId) {
        Period period = periodMapper.findById(periodId)
                .filter(p -> p.getFamilyId() == familyId)
                .orElseThrow(() -> new IllegalArgumentException("周期不存在: " + periodId));
        Account account = requireAccount(familyId, accountId);
        Map<Long, Member> members = memberMapper.findActiveByFamily(familyId).stream()
                .collect(Collectors.toMap(Member::getId, Function.identity()));
        Map<Long, Account> allById = accountMapper.findAllByFamily(familyId).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        PeriodSnapshot current = snapshotMapper.findByPeriodAndAccount(periodId, accountId).orElse(null);
        SnapshotTodo todo = snapshotTodoMapper.findByPeriodAndAccount(periodId, accountId).orElse(null);
        return toRow(account, members, allById, current, todo, period);
    }

    @Transactional
    public EntryRow submitBalance(long familyId,
                                  long memberId,
                                  long periodId,
                                  long accountId,
                                  BigDecimal newBalance,
                                  List<CashFlowLine> cashFlowLines,
                                  List<TransferLine> transferLines,
                                  String note) {
        Period period = requireOpenPeriod(familyId, periodId);
        Account account = requireAccount(familyId, accountId);
        BigDecimal normalizedBalance = normalizeBalance(account, newBalance);
        boolean overwriting = snapshotMapper.findByPeriodAndAccount(periodId, accountId).isPresent();

        snapshotMapper.upsert(PeriodSnapshot.builder()
                .periodId(periodId)
                .accountId(accountId)
                .endBalance(normalizedBalance)
                .submittedBy(memberId)
                .note(blankToNull(note))
                .build());

        for (CashFlowLine line : cashFlowLines == null ? List.<CashFlowLine>of() : cashFlowLines) {
            insertCashFlow(period, account, memberId, line);
        }
        for (TransferLine line : transferLines == null ? List.<TransferLine>of() : transferLines) {
            insertTransfer(period, familyId, accountId, line.toAccountId(), line.amount(), line.note(), memberId, false);
        }

        adjustLoanDraft(period, account, normalizedBalance, memberId);
        snapshotTodoMapper.markDone(periodId, accountId, memberId);

        auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "period_snapshot", accountId,
                overwriting ? "覆盖余额快照" : "提交余额快照");
        EntryRow row = rowFor(familyId, memberId, periodId, accountId);
        if ((account.getType() == AccountType.CASH || account.getType() == AccountType.LOAN)
                && row.unexplained() != null
                && row.unexplained().compareTo(new BigDecimal("0.00")) != 0) {
            auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "period_snapshot", accountId,
                    "余额轧差未解释: " + row.unexplainedLabel());
        }
        return row;
    }

    @Transactional
    public EntryRow addCashFlow(long familyId,
                                long memberId,
                                long periodId,
                                long accountId,
                                CashFlowKind kind,
                                String categoryCode,
                                BigDecimal amount,
                                String note) {
        Period period = requireOpenPeriod(familyId, periodId);
        Account account = requireAccount(familyId, accountId);
        BigDecimal delta = kind == CashFlowKind.INCOME ? amount : amount.negate();
        applyDeltaToBalance(period, account, memberId, delta,
                (kind == CashFlowKind.INCOME ? "+收入 " : "-支出 ") + money(amount));
        insertCashFlow(period, account, memberId, new CashFlowLine(kind, categoryCode, amount, note));
        // v0.2 bug 修(2026-05-10): cash_flow 路径必须把 todo 标 DONE,
        // 否则 forceClose 会因 PENDING 把"上期末"覆盖回 snapshot,丢失真实数据
        snapshotTodoMapper.markDone(periodId, accountId, memberId);
        auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "cash_flow", accountId,
                "新增现金流 " + kind + " " + money(amount));
        return rowFor(familyId, memberId, periodId, accountId);
    }

    /** v0.2 FR-32 · 软删现金流(同时反向冲销余额) */
    @Transactional
    public EntryRow softDeleteCashFlow(long familyId, long memberId, long cashFlowId) {
        CashFlow cf = cashFlowMapper.findById(cashFlowId)
                .orElseThrow(() -> new IllegalArgumentException("现金流不存在: " + cashFlowId));
        Period period = requireOpenPeriod(familyId, cf.getPeriodId());
        Account account = requireAccount(familyId, cf.getAccountId());
        // 反向冲销:INCOME 删 → balance -amount;EXPENSE 删 → balance +amount
        BigDecimal delta = cf.getKind() == CashFlowKind.INCOME ? cf.getAmount().negate() : cf.getAmount();
        applyDeltaToBalance(period, account, memberId, delta,
                "✕ 撤销 " + cf.getKind() + " " + money(cf.getAmount()));
        cashFlowMapper.softDelete(cashFlowId);
        auditLogService.record(familyId, memberId, AuditLogType.CASH_FLOW_WRITE, "cash_flow", cashFlowId,
                "软删现金流 " + cf.getKind() + " " + money(cf.getAmount()));
        return rowFor(familyId, memberId, period.getId(), cf.getAccountId());
    }

    /** v0.2 FR-32 · 软删转账(同时反向冲销 from + to 两端余额) */
    @Transactional
    public EntryRow softDeleteTransfer(long familyId, long memberId, long transferId) {
        Transfer t = transferMapper.findById(transferId)
                .orElseThrow(() -> new IllegalArgumentException("转账不存在: " + transferId));
        Period period = requireOpenPeriod(familyId, t.getPeriodId());
        Account from = requireAccount(familyId, t.getFromAccountId());
        Account to = requireAccount(familyId, t.getToAccountId());
        // 反向:from +amount,to -amount
        applyDeltaToBalance(period, from, memberId, t.getAmount(),
                "✕ 撤销划出到 " + to.getDisplayName() + " " + money(t.getAmount()));
        applyDeltaToBalance(period, to, memberId, t.getAmount().negate(),
                "✕ 撤销来自 " + from.getDisplayName() + " " + money(t.getAmount()));
        transferMapper.softDelete(transferId);
        auditLogService.record(familyId, memberId, AuditLogType.TRANSFER_CREATE, "transfer", transferId,
                "软删转账 " + from.getDisplayName() + " → " + to.getDisplayName() + " " + money(t.getAmount()));
        return rowFor(familyId, memberId, period.getId(), t.getFromAccountId());
    }

    @Transactional
    public EntryRow addTransfer(long familyId,
                                long memberId,
                                long periodId,
                                long fromAccountId,
                                long toAccountId,
                                BigDecimal amount,
                                String note,
                                boolean confirmDuplicate) {
        Period period = requireOpenPeriod(familyId, periodId);
        Account fromAccount = requireAccount(familyId, fromAccountId);
        Account toAccount = requireAccount(familyId, toAccountId);
        // 划转 A→B:A 余额 -amount,B 余额 +amount
        applyDeltaToBalance(period, fromAccount, memberId, amount.negate(),
                "↱ 划出到 " + toAccount.getDisplayName() + " " + money(amount));
        applyDeltaToBalance(period, toAccount, memberId, amount,
                "↳ 收到来自 " + fromAccount.getDisplayName() + " " + money(amount));
        insertTransfer(period, familyId, fromAccountId, toAccountId, amount, note, memberId, confirmDuplicate);
        // v0.2 bug 修(2026-05-10): 转账路径双端都要把 todo 标 DONE,
        // 否则 forceClose 会因 PENDING 把"上期末"覆盖回 snapshot,丢失真实数据
        snapshotTodoMapper.markDone(periodId, fromAccountId, memberId);
        snapshotTodoMapper.markDone(periodId, toAccountId, memberId);
        auditLogService.record(familyId, memberId, AuditLogType.TRANSFER_CREATE, "transfer", fromAccountId,
                "新增转账 " + fromAccountId + " → " + toAccountId + " " + money(amount));
        return rowFor(familyId, memberId, periodId, fromAccountId);
    }

    /**
     * 用户点 +收入 / -支出 / ↔划转 等"快捷按钮"时,把 delta 直接累加到本期余额上:
     *   - baseBalance = 当前 snapshot.end_balance(若没填过,fallback 到上期末,再 fallback 到 0)
     *   - newBalance = baseBalance + delta
     *   - upsert snapshot,note 标"快捷按钮调整"
     * 这样 4 种场景:
     *   1. 余额不变 — 用户进入页面时输入框默认显示上期末;不动直接提交即等于"本期=上期"
     *   2. 余额变化(原因不明)— 用户在余额输入框填新值,snapshot 直接覆盖
     *   3. A→B 转 500 — 自动 A 余额 -500、B 余额 +500
     *   4. 收入 4000 快捷 — 自动余额 +4000
     * 见 PRD § 2.4 / FR-7~9 / §7.9。
     */
    private void applyDeltaToBalance(Period period, Account account, long memberId,
                                      BigDecimal delta, String reason) {
        Optional<PeriodSnapshot> currentOpt = snapshotMapper.findByPeriodAndAccount(period.getId(), account.getId());
        BigDecimal base;
        if (currentOpt.isPresent()) {
            base = currentOpt.get().getEndBalance();
        } else {
            PeriodSnapshot prevSnap = snapshotMapper.findLatestBefore(account.getId(), period.getPeriodStart(), 1)
                    .stream().findFirst().orElse(null);
            base = prevSnap == null ? BigDecimal.ZERO : prevSnap.getEndBalance();
        }
        BigDecimal newBalance = base.add(delta).setScale(2, RoundingMode.HALF_EVEN);
        snapshotMapper.upsert(PeriodSnapshot.builder()
                .periodId(period.getId())
                .accountId(account.getId())
                .endBalance(newBalance)
                .submittedBy(memberId)
                .note(reason + " · 余额 " + base + " → " + newBalance)
                .build());
        auditLogService.record(period.getFamilyId(), memberId, AuditLogType.SNAPSHOT_WRITE,
                "period_snapshot", account.getId(),
                reason + ":余额 " + base + " → " + newBalance);
    }

    @Transactional
    public EntryRow quickTransfer(long familyId,
                                  long memberId,
                                  long fromAccountId,
                                  Long periodId,
                                  long toAccountId,
                                  BigDecimal amount,
                                  String note,
                                  boolean confirmDuplicate) {
        Period period = periodId == null
                ? periodMapper.findCurrentOpen(familyId).orElseThrow(() -> new IllegalStateException("当前没有 OPEN 周期"))
                : requireOpenPeriod(familyId, periodId);
        return addTransfer(familyId, memberId, period.getId(), fromAccountId, toAccountId, amount, note, confirmDuplicate);
    }

    private EntryRow toRow(Account account,
                           Map<Long, Member> members,
                           Map<Long, Account> allAccountsById,
                           PeriodSnapshot current,
                           SnapshotTodo todo,
                           Period period) {
        PeriodSnapshot previous = snapshotMapper.findLatestBefore(account.getId(), period.getPeriodStart(), 1)
                .stream()
                .findFirst()
                .orElse(null);
        ReconciliationTotals totals = reconciliationTotals(period.getId(), account.getId());
        BigDecimal currentBalance = current == null ? null : current.getEndBalance();
        BigDecimal previousBalance = previous == null ? null : previous.getEndBalance();
        BigDecimal effectiveBalance = currentBalance != null
                ? currentBalance
                : (todo == null ? null : todo.getPrefilledBalance());
        BigDecimal delta = currentBalance != null && previousBalance != null
                ? currentBalance.subtract(previousBalance)
                : null;
        BigDecimal unexplained = effectiveBalance == null || previousBalance == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN)
                : ReconciliationCalculator.unexplained(
                        effectiveBalance,
                        previousBalance,
                        totals.income(),
                        totals.expense(),
                        totals.transferIn(),
                        totals.transferOut());
        Member owner = account.getPrimaryOwnerMemberId() == null ? null : members.get(account.getPrimaryOwnerMemberId());
        boolean done = current != null || (todo != null && todo.getStatus() == TodoStatus.DONE);
        String currentLabel = currentBalance == null && todo != null && todo.getPrefilledBalance() != null
                ? "预填 " + MoneyFormat.format(account.getCurrency(), todo.getPrefilledBalance())
                : MoneyFormat.format(account.getCurrency(), currentBalance);
        String warning = (account.getType() == AccountType.CASH || account.getType() == AccountType.LOAN)
                && unexplained.signum() != 0
                ? "CASH/LOAN 出现未解释变化,建议补收入、支出或转账"
                : null;
        // PRD §2.4 / FR-9:本期划转明细(供接收方看到"已收到来自 X 的 200")
        List<EntryRow.TransferRef> incoming = new ArrayList<>();
        List<EntryRow.TransferRef> outgoing = new ArrayList<>();
        // 同时为本期 ledger(本期所有流水合并视图,PRD §7.9 / FR-7~9)收集划转条目
        List<EntryRow.LedgerEntry> ledger = new ArrayList<>();
        for (Transfer t : transferMapper.findCommittedByPeriodAndAccount(period.getId(), account.getId())) {
            if (t.getToAccountId().equals(account.getId())) {
                Account from = allAccountsById.get(t.getFromAccountId());
                String name = from == null ? "其他账户" : from.getDisplayName();
                incoming.add(new EntryRow.TransferRef(name, t.getAmount(),
                        MoneyFormat.format(account.getCurrency(), t.getAmount())));
                ledger.add(new EntryRow.LedgerEntry(
                        EntryRow.LedgerKind.TRANSFER_IN,
                        t.getSubmittedAt(),
                        t.getAmount(),
                        "+" + MoneyFormat.format(account.getCurrency(), t.getAmount()),
                        name,
                        t.getNote(),
                        t.getId(),
                        period.getStatus() == PeriodStatus.OPEN));
            } else if (t.getFromAccountId().equals(account.getId())) {
                Account to = allAccountsById.get(t.getToAccountId());
                String name = to == null ? "其他账户" : to.getDisplayName();
                outgoing.add(new EntryRow.TransferRef(name, t.getAmount(),
                        MoneyFormat.format(account.getCurrency(), t.getAmount())));
                ledger.add(new EntryRow.LedgerEntry(
                        EntryRow.LedgerKind.TRANSFER_OUT,
                        t.getSubmittedAt(),
                        t.getAmount(),
                        "−" + MoneyFormat.format(account.getCurrency(), t.getAmount()),
                        name,
                        t.getNote(),
                        t.getId(),
                        period.getStatus() == PeriodStatus.OPEN));
            }
        }
        for (CashFlow cf : cashFlowMapper.findByPeriodAndAccount(period.getId(), account.getId())) {
            EntryRow.LedgerKind k = cf.getKind() == CashFlowKind.INCOME
                    ? EntryRow.LedgerKind.INCOME : EntryRow.LedgerKind.EXPENSE;
            String sign = cf.getKind() == CashFlowKind.INCOME ? "+" : "−";
            ledger.add(new EntryRow.LedgerEntry(
                    k,
                    cf.getSubmittedAt(),
                    cf.getAmount(),
                    sign + MoneyFormat.format(account.getCurrency(), cf.getAmount()),
                    cf.getCategoryCode(),
                    cf.getNote(),
                    cf.getId(),
                    period.getStatus() == PeriodStatus.OPEN));
        }
        if (current != null) {
            // v0.4.4:历史遗留的英文系统标记替换为中文,避免用户面暴露内部代号
            String snapNote = current.getNote();
            if (snapNote != null && snapNote.startsWith("auto-stock-valuation")) {
                snapNote = com.family.finance.service.stock.AccountValuationService.SYSTEM_VALUATION_NOTE;
            }
            ledger.add(new EntryRow.LedgerEntry(
                    EntryRow.LedgerKind.SNAPSHOT,
                    current.getSubmittedAt(),
                    current.getEndBalance(),
                    "= " + MoneyFormat.format(account.getCurrency(), current.getEndBalance()),
                    null,
                    snapNote,
                    null,
                    period.getStatus() == PeriodStatus.OPEN));
        }
        // v0.4.1 FR-52f · STOCK 账户估值事件作为第 4 种流水
        if (account.getType() == AccountType.STOCK) {
            try {
                for (StockValuationEvent ev : stockValuationEventMapper.findByAccountAndPeriod(
                        account.getId(), period.getId())) {
                    String sign = ev.getDelta().signum() >= 0 ? "+" : "−";
                    String label = "估值变动 · " + triggerCn(ev.getTriggerKind());
                    String note = ev.getNote() != null ? ev.getNote()
                        : (ev.getPrevBalance() != null
                            ? "从 " + MoneyFormat.format(account.getCurrency(), ev.getPrevBalance())
                              + " → " + MoneyFormat.format(account.getCurrency(), ev.getNewBalance())
                            : null);
                    ledger.add(new EntryRow.LedgerEntry(
                        EntryRow.LedgerKind.VALUATION,
                        ev.getTriggeredAt(),
                        ev.getDelta().abs(),
                        sign + MoneyFormat.format(account.getCurrency(), ev.getDelta().abs()),
                        label,
                        note,
                        ev.getId(),
                        false  // 估值事件不可删除 · 不显操作按钮
                    ));
                }
            } catch (Exception ignored) {
                // ledger 渲染失败不阻塞整体页面
            }
        }
        ledger.sort((a, b) -> {
            if (a.occurredAt() == null && b.occurredAt() == null) return 0;
            if (a.occurredAt() == null) return 1;
            if (b.occurredAt() == null) return -1;
            return a.occurredAt().compareTo(b.occurredAt());
        });

        return new EntryRow(
                account,
                owner == null ? "共同" : owner.getDisplayName(),
                todo,
                current,
                previous,
                delta,
                totals.income(),
                totals.expense(),
                totals.transferIn(),
                totals.transferOut(),
                unexplained,
                currentLabel,
                MoneyFormat.format(account.getCurrency(), previousBalance),
                MoneyFormat.formatDelta(account.getCurrency(), delta),
                MoneyFormat.formatDelta(account.getCurrency(), unexplained),
                warning,
                done,
                // PRD FR-10 智能转账推断:|未解释| > ¥3000 阈值
                unexplained.abs().compareTo(new BigDecimal("3000")) > 0
                && account.getType() != AccountType.LOAN,
                incoming,
                outgoing,
                ledger
        );
    }

    private void insertCashFlow(Period period, Account account, long memberId, CashFlowLine line) {
        if (line == null || line.amount() == null || line.amount().signum() == 0) {
            return;
        }
        if (line.kind() == null) {
            throw new IllegalArgumentException("现金流类型必填");
        }
        if (line.categoryCode() == null || line.categoryCode().isBlank()) {
            throw new IllegalArgumentException("现金流类别必填");
        }
        BigDecimal amount = positiveMoney(line.amount());
        cashFlowMapper.insert(CashFlow.builder()
                .periodId(period.getId())
                .accountId(account.getId())
                .kind(line.kind())
                .categoryCode(line.categoryCode())
                .amount(amount)
                .occurredAt(period.getPeriodEnd())
                .note(blankToNull(line.note()))
                .submittedBy(memberId)
                .build());
    }

    private Transfer insertTransfer(Period period,
                                    long familyId,
                                    long fromAccountId,
                                    long toAccountId,
                                    BigDecimal amount,
                                    String note,
                                    long memberId,
                                    boolean confirmDuplicate) {
        if (fromAccountId == toAccountId) {
            throw new IllegalArgumentException("转出/转入账户不能相同");
        }
        requireAccount(familyId, fromAccountId);
        requireAccount(familyId, toAccountId);
        BigDecimal normalized = positiveMoney(amount);
        int duplicate = transferMapper.countRecentDuplicate(period.getId(), fromAccountId, toAccountId, normalized);
        if (duplicate > 0 && !confirmDuplicate) {
            throw new IllegalArgumentException("看起来像 24 小时内重复转账,请确认后再提交");
        }
        Transfer transfer = Transfer.builder()
                .periodId(period.getId())
                .fromAccountId(fromAccountId)
                .toAccountId(toAccountId)
                .amount(normalized)
                .occurredAt(period.getPeriodEnd())
                .note(blankToNull(note))
                .submittedBy(memberId)
                .draft(false)
                .build();
        transferMapper.insert(transfer);
        return transfer;
    }

    /**
     * 历史版本会在用户改 LOAN 余额时"自动调整 / 确认"一笔从 default_payment_source 的 transfer。
     * 经产品反馈,LOAN 余额修改应**与其他账户解耦**(避免暗中改招行卡等"还款来源"账户的余额),
     * 留给用户在 LOAN 行的快捷划转按钮显式登记。本方法保留为空 op,
     * 兼容既有调用方但不再产生联动副作用。详见 PRD §7.9 第六批维护。
     */
    private void adjustLoanDraft(Period period, Account loan, BigDecimal newBalance, long memberId) {
        // intentionally no-op
    }

    private ReconciliationTotals reconciliationTotals(long periodId, long accountId) {
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        for (CashFlow cashFlow : cashFlowMapper.findByPeriodAndAccount(periodId, accountId)) {
            if (cashFlow.getKind() == CashFlowKind.INCOME) {
                income = income.add(cashFlow.getAmount());
            } else {
                expense = expense.add(cashFlow.getAmount());
            }
        }
        BigDecimal transferIn = BigDecimal.ZERO;
        BigDecimal transferOut = BigDecimal.ZERO;
        for (Transfer transfer : transferMapper.findCommittedByPeriodAndAccount(periodId, accountId)) {
            if (transfer.getToAccountId().equals(accountId)) {
                transferIn = transferIn.add(transfer.getAmount());
            }
            if (transfer.getFromAccountId().equals(accountId)) {
                transferOut = transferOut.add(transfer.getAmount());
            }
        }
        return new ReconciliationTotals(
                income.setScale(2, RoundingMode.HALF_EVEN),
                expense.setScale(2, RoundingMode.HALF_EVEN),
                transferIn.setScale(2, RoundingMode.HALF_EVEN),
                transferOut.setScale(2, RoundingMode.HALF_EVEN)
        );
    }

    private Period requireOpenPeriod(long familyId, long periodId) {
        Period period = periodMapper.findById(periodId)
                .filter(p -> p.getFamilyId() == familyId)
                .orElseThrow(() -> new IllegalArgumentException("周期不存在: " + periodId));
        if (period.getStatus() != PeriodStatus.OPEN) {
            throw new IllegalStateException("周期已关闭,请先重开再修改");
        }
        return period;
    }

    private Account requireAccount(long familyId, long accountId) {
        return accountMapper.findById(accountId)
                .filter(account -> account.getFamilyId() == familyId)
                .orElseThrow(() -> new IllegalArgumentException("账户不存在: " + accountId));
    }

    private BigDecimal normalizeBalance(Account account, BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("余额必填");
        }
        BigDecimal scaled = value.setScale(2, RoundingMode.HALF_EVEN);
        if (account.getType() == AccountType.LOAN && scaled.signum() > 0) {
            return scaled.negate();
        }
        return scaled;
    }

    private BigDecimal positiveMoney(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("金额必填");
        }
        BigDecimal amount = value.abs().setScale(2, RoundingMode.HALF_EVEN);
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("金额必须大于 0");
        }
        return amount;
    }

    private String money(BigDecimal amount) {
        return amount == null ? "—" : amount.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record CashFlowLine(CashFlowKind kind, String categoryCode, BigDecimal amount, String note) {
    }

    public record TransferLine(Long toAccountId, BigDecimal amount, String note) {
    }

    private record ReconciliationTotals(BigDecimal income, BigDecimal expense, BigDecimal transferIn, BigDecimal transferOut) {
    }
}
