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
import com.family.finance.domain.stock.Market;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.domain.stock.StockValuationEvent;
import com.family.finance.domain.stock.ValuationMode;
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
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class EntryService {

    /** 单家庭模式 · 见 prd §22.3 类 A */
    private static final long FAMILY_ID = 1L;

    private final AccountMapper accountMapper;
    private final MemberMapper memberMapper;
    private final PeriodMapper periodMapper;
    private final SnapshotMapper snapshotMapper;
    private final SnapshotTodoMapper snapshotTodoMapper;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher; // v1.1.1 lens 缓存失效事件
    private final CashFlowMapper cashFlowMapper;
    private final TransferMapper transferMapper;
    private final AuditLogService auditLogService;
    private final com.family.finance.service.config.FamilyConfigService configService;
    /** v0.4.1 FR-52f · 股票估值事件 · ledger 显示 */
    private final StockValuationEventMapper stockValuationEventMapper;
    /** v0.12 · 收入侧:类目↔账户类型校验 + 股票收入落 CASH 现金行 */
    private final com.family.finance.repository.CashFlowCategoryMapper cashFlowCategoryMapper;
    private final com.family.finance.service.stock.StockHoldingService stockHoldingService;

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
            insertTransfer(period, familyId, accountId, line.toAccountId(), line.amount(), line.toAmount(), line.note(), memberId, false);
        }

        adjustLoanDraft(period, account, normalizedBalance, memberId);
        snapshotTodoMapper.markDone(periodId, accountId, memberId);

        auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "period_snapshot", accountId,
                overwriting ? "覆盖余额快照" : "提交余额快照");
        eventPublisher.publishEvent(new com.family.finance.service.lens.LensStaleEvent(familyId)); // v1.1.1 透视缓存后台换新

        EntryRow row = rowFor(familyId, memberId, periodId, accountId);
        if ((account.getType() == AccountType.CASH || account.getType() == AccountType.LOAN)
                && row.unexplained() != null
                && row.unexplained().compareTo(new BigDecimal("0.00")) != 0) {
            auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "period_snapshot", accountId,
                    "余额轧差未解释: " + row.unexplainedLabel());
        }
        return row;
    }

    /**
     * v0.17.x · 接受贷款趋势预测:把该贷款本期余额设为 predicted(prev+Δ 夹≤0),并<b>复刻旧逻辑</b>
     * 起草一笔还款转账(默认还款来源 → 贷款,金额 = 本期还款额),标 todo done。
     * 与旧 PeriodOpener.applyLoanPrefill 做的事完全一致,只是由用户点击触发而非开账静默执行。
     */
    @Transactional
    public EntryRow acceptLoanPrediction(long familyId, long memberId, long periodId, long accountId) {
        Period period = requireOpenPeriod(familyId, periodId);
        Account loan = requireAccount(familyId, accountId);
        if (loan.getType() != AccountType.LOAN) {
            throw new IllegalArgumentException("仅贷款账户支持趋势预填");
        }
        List<PeriodSnapshot> last2 = snapshotMapper.findLatestBefore(accountId, period.getPeriodStart(), 2);
        if (last2.isEmpty()) {
            throw new IllegalStateException("无历史余额,无法预测");
        }
        BigDecimal prev = last2.get(0).getEndBalance();
        BigDecimal prevPrev = last2.size() >= 2 ? last2.get(1).getEndBalance() : null;
        BigDecimal predicted = PeriodOpener.predictLoanBalance(prev, prevPrev);

        snapshotMapper.upsert(PeriodSnapshot.builder()
                .periodId(periodId)
                .accountId(accountId)
                .endBalance(predicted)
                .submittedBy(memberId)
                .note("按上两月趋势预测本期还款")
                .build());

        // 复刻旧逻辑:草稿还款转账(默认还款来源 → 贷款,金额 = predicted − prev,>0 才起草)
        BigDecimal repay = predicted.subtract(prev);
        if (loan.getDefaultPaymentSourceAccountId() != null && repay.signum() > 0) {
            transferMapper.insert(com.family.finance.domain.transfer.Transfer.builder()
                    .periodId(periodId)
                    .fromAccountId(loan.getDefaultPaymentSourceAccountId())
                    .toAccountId(accountId)
                    .amount(repay)
                    .occurredAt(period.getPeriodEnd())
                    .note("按上两月趋势预填还款")
                    .submittedBy(memberId)
                    .draft(true)
                    .build());
        }

        snapshotTodoMapper.markDone(periodId, accountId, memberId);
        auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "period_snapshot", accountId,
                "接受贷款趋势预测 " + MoneyFormat.format(loan.getCurrency(), predicted));
        eventPublisher.publishEvent(new com.family.finance.service.lens.LensStaleEvent(familyId)); // v1.1.1 透视缓存后台换新

        return rowFor(familyId, memberId, periodId, accountId);
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
        creditAccountBalance(familyId, period, account, memberId, delta,
                (kind == CashFlowKind.INCOME ? "+收入 " : "-支出 ") + money(amount));
        insertCashFlow(period, account, memberId, new CashFlowLine(kind, categoryCode, amount, note));
        // v0.2 bug 修(2026-05-10): cash_flow 路径必须把 todo 标 DONE,
        // 否则 forceClose 会因 PENDING 把"上期末"覆盖回 snapshot,丢失真实数据
        snapshotTodoMapper.markDone(periodId, accountId, memberId);
        auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "cash_flow", accountId,
                "新增现金流 " + kind + " " + money(amount));
        eventPublisher.publishEvent(new com.family.finance.service.lens.LensStaleEvent(familyId)); // v1.1.1 透视缓存后台换新

        return rowFor(familyId, memberId, periodId, accountId);
    }

    /**
     * v0.12 FR-140/141/143/144/147 · 收入侧结构化录入:一笔收入 = 金额 + 类目 + 目标账户。
     * 校验类目↔账户类型(服务端红线);写 cash_flow(INCOME · is_adjustment=0 · 真实外部流入,被 PnL 剔除)
     * + 入账(股票账户落 CASH 现金行,扛过估值刷新)+ 标 todo done。
     */
    @Transactional
    public EntryRow recordIncome(long familyId, long memberId, long periodId,
                                 long accountId, String categoryCode, BigDecimal amount, String note) {
        Period period = requireOpenPeriod(familyId, periodId);
        Account account = requireAccount(familyId, accountId);
        if (account.getType() == AccountType.LOAN) {
            throw new IllegalArgumentException("负债账户不能录入收入");
        }
        var cat = requireIncomeCategoryForAccount(categoryCode, account);
        BigDecimal amt = positiveMoney(amount);
        creditAccountBalance(familyId, period, account, memberId, amt,
                "+收入 " + cat.getDisplayName() + " " + money(amt));
        insertCashFlow(period, account, memberId, new CashFlowLine(CashFlowKind.INCOME, categoryCode, amt, note));
        snapshotTodoMapper.markDone(periodId, accountId, memberId);
        auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "cash_flow", accountId,
                "收入录入 " + cat.getDisplayName() + " " + money(amt) + " → " + account.getDisplayName());
        eventPublisher.publishEvent(new com.family.finance.service.lens.LensStaleEvent(familyId)); // v1.1.1 透视缓存后台换新
        return rowFor(familyId, memberId, periodId, accountId);
    }

    /** 校验收入类目存在 + 是 INCOME + 绑定账户类型与目标账户一致(NULL=不限)· 返回类目。 */
    private com.family.finance.domain.flow.CashFlowCategory requireIncomeCategoryForAccount(
            String categoryCode, Account account) {
        var cat = cashFlowCategoryMapper.findByCode(categoryCode)
                .orElseThrow(() -> new IllegalArgumentException("收入类目不存在: " + categoryCode));
        if (!"INCOME".equals(cat.getKind())) {
            throw new IllegalArgumentException("类目「" + cat.getDisplayName() + "」不是收入类目");
        }
        // FR-147 服务端红线:类目绑定的账户类型必须与目标账户一致 · 防「工资录进股票 / 股息录进现金」
        if (cat.getAccountType() != null && !cat.getAccountType().equals(account.getType().name())) {
            throw new IllegalArgumentException("类目「" + cat.getDisplayName() + "」只能录入 "
                    + cat.getAccountType() + " 类账户,不能录入" + account.getType() + "账户");
        }
        return cat;
    }

    /**
     * v0.12 FR-144/150 · 股票收入 · 归属到「已有持仓」+股数(上市 AUTO / 未上市 MANUAL)。
     * 计值:上市按最新已知价 × FX、未上市按持仓单股估值;value = addShares × 单价(账户币种)。
     * shares += addShares(持久)+ applyDeltaToBalance(+value) 立即入账;记 cash_flow(INCOME · is_adjustment=0
     * · ref_holding_id/ref_shares 供删除冲回)。外部流入不进 PnL → 收益率不虚高。
     */
    @Transactional
    public EntryRow recordStockIncomeExistingHolding(long familyId, long memberId, long periodId,
                                                     long accountId, long holdingId, BigDecimal addShares,
                                                     String categoryCode, String note) {
        Period period = requireOpenPeriod(familyId, periodId);
        Account account = requireStockAccount(familyId, accountId);
        var cat = requireIncomeCategoryForAccount(categoryCode, account);
        StockHolding h = stockHoldingService.require(familyId, holdingId);
        if (!h.getAccountId().equals(accountId)) {
            throw new IllegalArgumentException("持仓不属于该账户");
        }
        if (h.getValuationMode() != ValuationMode.AUTO && h.getValuationMode() != ValuationMode.MANUAL) {
            throw new IllegalArgumentException("仅上市/未上市持仓可按股数录收入(券商现金请用现金入账)");
        }
        BigDecimal shares = positiveShares(addShares);
        BigDecimal unit = stockHoldingService.currentUnitValueInAccountCcy(familyId, h);
        if (unit == null) {
            throw new IllegalArgumentException("该持仓暂无价格,请先在持仓页刷新股价后再录入");
        }
        BigDecimal value = shares.multiply(unit).setScale(2, RoundingMode.HALF_EVEN);
        stockHoldingService.addShares(familyId, holdingId, shares);
        return finishStockShareIncome(familyId, memberId, period, account, cat.getDisplayName(),
                categoryCode, value, holdingId, shares,
                shareIncomeNote(shares, h.getDisplayName(), note));
    }

    /** v0.12 · 股票收入 · 新建「未上市」持仓入账(名称 + 股数 + 单股估值)。 */
    @Transactional
    public EntryRow recordStockIncomeNewManual(long familyId, long memberId, long periodId,
                                               long accountId, String displayName, BigDecimal shares,
                                               BigDecimal unitValue, String categoryCode, String note) {
        Period period = requireOpenPeriod(familyId, periodId);
        Account account = requireStockAccount(familyId, accountId);
        var cat = requireIncomeCategoryForAccount(categoryCode, account);
        BigDecimal sh = positiveShares(shares);
        BigDecimal unit = positiveUnitValue(unitValue);   // issue#3:单股估值保高精度,不像金额那样压成 2 位
        StockHolding h = stockHoldingService.createManual(familyId, accountId, displayName, sh, unit);
        BigDecimal value = sh.multiply(unit).setScale(2, RoundingMode.HALF_EVEN);
        return finishStockShareIncome(familyId, memberId, period, account, cat.getDisplayName(),
                categoryCode, value, h.getId(), sh, shareIncomeNote(sh, h.getDisplayName(), note));
    }

    /**
     * v0.12 · 股票收入 · 新建「上市」持仓入账(代码 + 市场 + 股数);按最新已知价计值。
     * 调用方(controller)应先 fetchMarket 确保有价;无价则抛错提示先刷价。收入非买入 → 不扣现金。
     */
    @Transactional
    public EntryRow recordStockIncomeNewAuto(long familyId, long memberId, long periodId,
                                             long accountId, String displayName, String ticker, Market market,
                                             BigDecimal shares, String currency, String categoryCode, String note) {
        Period period = requireOpenPeriod(familyId, periodId);
        Account account = requireStockAccount(familyId, accountId);
        var cat = requireIncomeCategoryForAccount(categoryCode, account);
        BigDecimal sh = positiveShares(shares);
        StockHolding h = stockHoldingService.createAuto(familyId, accountId, displayName, ticker, market,
                sh, null, currency, false);
        BigDecimal unit = stockHoldingService.currentUnitValueInAccountCcy(familyId, h);
        if (unit == null) {
            throw new IllegalArgumentException("该股票暂无价格,请稍后在持仓页刷新股价后再录入");
        }
        BigDecimal value = sh.multiply(unit).setScale(2, RoundingMode.HALF_EVEN);
        return finishStockShareIncome(familyId, memberId, period, account, cat.getDisplayName(),
                categoryCode, value, h.getId(), sh, shareIncomeNote(sh, h.getDisplayName(), note));
    }

    /** 股票 +股数收入的收尾:立即入账(applyDelta)+ 记 cash_flow(带 ref 供冲回)+ 标 todo + 审计。 */
    private EntryRow finishStockShareIncome(long familyId, long memberId, Period period, Account account,
                                            String catLabel, String categoryCode, BigDecimal value,
                                            long holdingId, BigDecimal shares, String note) {
        applyDeltaToBalance(period, account, memberId, value,
                "+收入 " + catLabel + " " + money(value));
        cashFlowMapper.insert(CashFlow.builder()
                .periodId(period.getId())
                .accountId(account.getId())
                .kind(CashFlowKind.INCOME)
                .categoryCode(categoryCode)
                .amount(value)
                .occurredAt(period.getPeriodEnd())
                .note(blankToNull(note))
                .submittedBy(memberId)
                .adjustment(false)
                .refHoldingId(holdingId)
                .refShares(shares)
                .build());
        snapshotTodoMapper.markDone(period.getId(), account.getId(), memberId);
        auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "cash_flow", account.getId(),
                "股票收入 " + catLabel + " +" + shares.stripTrailingZeros().toPlainString() + " 股 "
                        + money(value) + " → " + account.getDisplayName());
        return rowFor(familyId, memberId, period.getId(), account.getId());
    }

    private String shareIncomeNote(BigDecimal shares, String holdingName, String userNote) {
        String base = "+" + shares.stripTrailingZeros().toPlainString() + " 股 · " + holdingName;
        if (userNote != null && !userNote.isBlank()) {
            base = base + " · " + userNote.trim();
        }
        return base.length() > 80 ? base.substring(0, 80) : base;
    }

    private BigDecimal positiveShares(BigDecimal value) {
        if (value == null) throw new IllegalArgumentException("股数必填");
        BigDecimal s = value.setScale(4, RoundingMode.HALF_EVEN);
        if (s.signum() <= 0) throw new IllegalArgumentException("股数必须大于 0");
        return s;
    }

    private Account requireStockAccount(long familyId, long accountId) {
        Account account = requireAccount(familyId, accountId);
        if (account.getType() != AccountType.STOCK) {
            throw new IllegalArgumentException("非股票账户不能按股数录入收入");
        }
        return account;
    }

    /** v0.2 FR-32 · 软删现金流(同时反向冲销余额) */
    @Transactional
    public EntryRow softDeleteCashFlow(long familyId, long memberId, long cashFlowId) {
        CashFlow cf = cashFlowMapper.findById(cashFlowId)
                .orElseThrow(() -> new IllegalArgumentException("现金流不存在: " + cashFlowId));
        Period period = requireOpenPeriod(familyId, cf.getPeriodId());
        Account account = requireAccount(familyId, cf.getAccountId());
        if (cf.getRefHoldingId() != null) {
            // v0.12 · 股票「+股数」收入:冲回持仓股数(持仓已归档/删则跳过)+ 冲回余额;不碰现金行
            BigDecimal shares = cf.getRefShares() == null ? BigDecimal.ZERO : cf.getRefShares();
            try {
                stockHoldingService.addShares(familyId, cf.getRefHoldingId(), shares.negate());
            } catch (Exception e) {
                log.warn("撤销股票收入冲回股数失败 · holding={} shares={}: {}",
                        cf.getRefHoldingId(), shares, e.toString());
            }
            applyDeltaToBalance(period, account, memberId, cf.getAmount().negate(),
                    "✕ 撤销股票收入(股数) " + money(cf.getAmount()));
        } else {
            // 反向冲销:INCOME 删 → balance -amount;EXPENSE 删 → balance +amount(股票账户走 CASH 现金行)
            BigDecimal delta = cf.getKind() == CashFlowKind.INCOME ? cf.getAmount().negate() : cf.getAmount();
            creditAccountBalance(familyId, period, account, memberId, delta,
                    "✕ 撤销 " + cf.getKind() + " " + money(cf.getAmount()));
        }
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
                                BigDecimal toAmount,   // v0.8 · 跨币种到账金额(转入账户币种);null=同币种
                                String note,
                                boolean confirmDuplicate) {
        Period period = requireOpenPeriod(familyId, periodId);
        Account fromAccount = requireAccount(familyId, fromAccountId);
        Account toAccount = requireAccount(familyId, toAccountId);
        // 跨币种:转出按 amount(转出账户币种),转入按 toAmount(转入账户币种);同币种 toAmount=null → 两端同 amount
        boolean crossCcy = !fromAccount.getCurrency().equals(toAccount.getCurrency());
        BigDecimal effToAmount = (toAmount != null) ? toAmount : amount;
        // 划转 A→B:A 余额 -amount,B 余额 +effToAmount
        applyDeltaToBalance(period, fromAccount, memberId, amount.negate(),
                "↱ 划出到 " + toAccount.getDisplayName() + " " + money(amount));
        applyDeltaToBalance(period, toAccount, memberId, effToAmount,
                "↳ 收到来自 " + fromAccount.getDisplayName() + " " + money(effToAmount));
        Transfer created = insertTransfer(period, familyId, fromAccountId, toAccountId, amount,
                crossCcy ? effToAmount : null, note, memberId, confirmDuplicate);
        // v1.2 · 再平衡计划核销事件(AFTER_COMMIT 消费 · 纯本地规则 · 失败不影响划转)
        if (created != null && created.getId() != null) {
            eventPublisher.publishEvent(new com.family.finance.service.review.RebalancePlanService.TransferCreatedEvent(
                    familyId, created.getId(), fromAccountId, toAccountId, amount));
        }
        // v0.2 bug 修(2026-05-10): 转账路径双端都要把 todo 标 DONE,
        // 否则 forceClose 会因 PENDING 把"上期末"覆盖回 snapshot,丢失真实数据
        snapshotTodoMapper.markDone(periodId, fromAccountId, memberId);
        snapshotTodoMapper.markDone(periodId, toAccountId, memberId);
        auditLogService.record(familyId, memberId, AuditLogType.TRANSFER_CREATE, "transfer", fromAccountId,
                "新增转账 " + fromAccountId + " → " + toAccountId + " " + money(amount));
        eventPublisher.publishEvent(new com.family.finance.service.lens.LensStaleEvent(familyId)); // v1.1.1 透视缓存后台换新
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

    /**
     * v0.12 · 把余额变动 delta 记到账户,按账户类型路由:
     *   - STOCK:落到账户「CASH 现金行」(adjustAccountCash,估值刷新只重算持仓市值、不动现金行 → 扛得住)
     *            + 同时 applyDeltaToBalance 立即反映到当期 snapshot(下次估值是「绝对重算含现金行」→ 不双计也不丢)。
     *   - 其它账户:仅 applyDeltaToBalance(snapshot 就是余额真值)。
     * 供收入侧 recordIncome + 账户级 addCashFlow/softDeleteCashFlow 统一复用。
     */
    private void creditAccountBalance(long familyId, Period period, Account account, long memberId,
                                      BigDecimal delta, String reason) {
        if (account.getType() == AccountType.STOCK) {
            stockHoldingService.adjustAccountCash(familyId, account.getId(), account.getCurrency(), delta);
        }
        applyDeltaToBalance(period, account, memberId, delta, reason);
    }

    @Transactional
    public EntryRow quickTransfer(long familyId,
                                  long memberId,
                                  long fromAccountId,
                                  Long periodId,
                                  long toAccountId,
                                  BigDecimal amount,
                                  BigDecimal toAmount,
                                  String note,
                                  boolean confirmDuplicate) {
        Period period = periodId == null
                ? periodMapper.findCurrentOpen(familyId).orElseThrow(() -> new IllegalStateException("当前没有 OPEN 周期"))
                : requireOpenPeriod(familyId, periodId);
        eventPublisher.publishEvent(new com.family.finance.service.lens.LensStaleEvent(familyId)); // v1.1.1 透视缓存后台换新
        return addTransfer(familyId, memberId, period.getId(), fromAccountId, toAccountId, amount, toAmount, note, confirmDuplicate);
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
                ? "余额出现未解释变化,建议补一笔收入 / 支出 / 转账对上账"
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
        // v0.4.1 FR-52f · 持仓账户估值事件作为第 4 种流水
        if (com.family.finance.service.stock.StockHoldingService.supportsHoldings(account.getType())) {
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
        // v0.4.22 · 倒序(新→旧)· 展开账户折叠看到的第一眼应该是最新最有价值的流水
        // null occurredAt(未知时间)放最末 · 不论升序降序都视为"信息价值最低"
        ledger.sort((a, b) -> {
            if (a.occurredAt() == null && b.occurredAt() == null) return 0;
            if (a.occurredAt() == null) return 1;
            if (b.occurredAt() == null) return -1;
            return b.occurredAt().compareTo(a.occurredAt());
        });

        // v0.17.x · 贷款趋势预测建议(填报页行内提示条)· prev+Δ 夹≤0(逻辑 follow 旧 predictLoanBalance)
        BigDecimal loanSuggestion = null;
        boolean showLoanPrompt = false;
        String loanSuggestionLabel = null;
        String loanSuggestionDeltaLabel = null;
        if (account.getType() == AccountType.LOAN && previousBalance != null) {
            List<PeriodSnapshot> last2 = snapshotMapper.findLatestBefore(account.getId(), period.getPeriodStart(), 2);
            BigDecimal prevPrev = last2.size() >= 2 ? last2.get(1).getEndBalance() : null;
            BigDecimal predicted = PeriodOpener.predictLoanBalance(previousBalance, prevPrev);
            boolean todoDone = todo != null && todo.getStatus() == TodoStatus.DONE;
            if (loanPromptVisible(predicted, previousBalance, currentBalance, todoDone)) {
                loanSuggestion = predicted;
                loanSuggestionLabel = MoneyFormat.format(account.getCurrency(), predicted);
                loanSuggestionDeltaLabel = MoneyFormat.formatDelta(account.getCurrency(), predicted.subtract(previousBalance));
                showLoanPrompt = true;
            }
        }

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
                // PRD FR-10 智能转账推断 · v0.4.18 阈值改读 ConfigService(默认 3000)
                unexplained.abs().compareTo(new BigDecimal(
                    Long.toString(configService.getLong(FAMILY_ID,
                        com.family.finance.service.config.FamilyConfigService.K_SMART_TRANSFER, 3000L)))) > 0
                && account.getType() != AccountType.LOAN,
                incoming,
                outgoing,
                ledger,
                loanSuggestion,
                showLoanPrompt,
                loanSuggestionLabel,
                loanSuggestionDeltaLabel
        );
    }

    /**
     * v0.17.x · 贷款趋势预测提示条是否显示 · 兼容闸(可测纯逻辑)。
     * 显示 iff:有实际建议(predicted≠prev)且用户未确认(todo 非 DONE)且当前 committed==上月值(新默认态)。
     * committed==prev 天然屏蔽老账期(旧代码已把 committed 写成预测值≠prev),不打扰、不回改。
     */
    static boolean loanPromptVisible(BigDecimal predicted, BigDecimal prev, BigDecimal committed, boolean todoDone) {
        return predicted != null && prev != null
                && predicted.compareTo(prev) != 0
                && !todoDone
                && committed != null && committed.compareTo(prev) == 0;
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
                                    BigDecimal toAmount,
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
                .toAmount(toAmount == null ? null : positiveMoney(toAmount))   // v0.8 · 跨币种到账额;null=同币种
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

    /**
     * 单股估值校验(issue#3)· 只校验 > 0,按 manual_value 列精度对齐到 6 位,
     * 不像 {@link #positiveMoney} 那样压成 2 位 —— 未上市单股估值 15.678 必须原样落库。
     */
    private BigDecimal positiveUnitValue(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("单股估值必填");
        }
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("单股估值必须大于 0");
        }
        return value.setScale(6, RoundingMode.HALF_EVEN);
    }

    private String money(BigDecimal amount) {
        return amount == null ? "—" : amount.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record CashFlowLine(CashFlowKind kind, String categoryCode, BigDecimal amount, String note) {
    }

    public record TransferLine(Long toAccountId, BigDecimal amount, BigDecimal toAmount, String note) {
    }

    private record ReconciliationTotals(BigDecimal income, BigDecimal expense, BigDecimal transferIn, BigDecimal transferOut) {
    }
}
