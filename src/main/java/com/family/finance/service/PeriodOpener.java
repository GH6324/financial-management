package com.family.finance.service;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.member.Member;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.snapshot.PeriodSnapshot;
import com.family.finance.domain.snapshot.SnapshotTodo;
import com.family.finance.domain.snapshot.TodoStatus;
import com.family.finance.domain.transfer.Transfer;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.MemberMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.SnapshotMapper;
import com.family.finance.repository.SnapshotTodoMapper;
import com.family.finance.repository.TransferMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Component
@RequiredArgsConstructor
public class PeriodOpener {

    private final FamilyService familyService;
    private final PeriodService periodService;
    private final AccountMapper accountMapper;
    private final MemberMapper memberMapper;
    private final PeriodMapper periodMapper;
    private final SnapshotMapper snapshotMapper;
    private final SnapshotTodoMapper snapshotTodoMapper;
    private final TransferMapper transferMapper;

    @Scheduled(cron = "0 30 0 * * *")
    @Transactional
    public void openIfDue() {
        LocalDate today = LocalDate.now();
        for (Family family : familyService.findAll()) {
            if (periodService.isPeriodStartDate(family.getPeriodType(), today)) {
                // v0.11.2 修 bug1:滚动开新期前,先 force-close 早于今天仍 OPEN 的旧期(避免旧期悬挂 OPEN)
                closePriorOpenPeriods(family, today);
                createPeriodAndTodos(family, today);
            }
        }
    }

    /**
     * v0.11.2 · 开新期时把「早于新期起始日」仍 OPEN 的旧期 force-close(待填账户按上期末延续 + 触发月报/指标)。
     * 放在建新期之前 → 新期预填从已落定的上期结转。无活跃成员则跳过(无法代签)。
     */
    private void closePriorOpenPeriods(Family family, LocalDate newStart) {
        Long systemMemberId = firstActiveMemberId(family.getId());
        if (systemMemberId == null) return;
        for (Period prior : periodMapper.findOpenBefore(family.getId(), newStart)) {
            try {
                periodService.forceClose(prior.getId(), systemMemberId);
            } catch (IllegalStateException alreadyClosed) {
                // 并发/重复触发下已非 OPEN,跳过
            }
        }
    }

    private Long firstActiveMemberId(long familyId) {
        return memberMapper.findActiveByFamily(familyId).stream()
                .findFirst().map(Member::getId).orElse(null);
    }

    /**
     * 测试 / 管理员手动触发:基于"最新已有周期"的下一个 period_start 立即开下一周期。
     * - 不存在任何周期 → 用今天的 currentPeriodStart
     * - 已有周期 → 取最大 period_start 计算 next
     * 同时生成 snapshot_todo + LOAN 预填(走 createPeriodAndTodos)。
     * 见 PRD §7.9 第五批维护。
     */
    @Transactional
    public Period openNextNow(long familyId) {
        Family family = familyService.require(familyId);
        LocalDate seed = periodService.findLatest(familyId, 1).stream()
                .findFirst()
                .map(Period::getPeriodStart)
                .map(start -> periodService.nextPeriodStart(family.getPeriodType(), start))
                .orElseGet(() -> periodService.currentPeriodStart(family.getPeriodType(), LocalDate.now()));
        // v0.11.2 修 bug1:开下一期前,先关早于它仍 OPEN 的旧期(与自动滚动同口径)
        closePriorOpenPeriods(family, seed);
        Period period = periodService.openIfAbsent(family, seed);
        // 复用既有 todo / LOAN 预填逻辑(已 idempotent)
        createPeriodAndTodos(family, seed);
        return period;
    }

    @Transactional
    public void createPeriodAndTodos(Family family, LocalDate periodStart) {
        Period period = periodService.openIfAbsent(family, periodStart);
        List<Account> accounts = accountMapper.findActiveByFamily(family.getId());
        Long systemMemberId = memberMapper.findActiveByFamily(family.getId()).stream()
                .findFirst()
                .map(Member::getId)
                .orElse(null);

        for (Account account : accounts) {
            if (snapshotTodoMapper.findByPeriodAndAccount(period.getId(), account.getId()).isPresent()) {
                continue;
            }
            SnapshotTodo todo = SnapshotTodo.builder()
                    .periodId(period.getId())
                    .accountId(account.getId())
                    .assignedMemberId(account.getPrimaryOwnerMemberId())
                    .status(TodoStatus.PENDING)
                    .build();

            // 计算"延续值":LOAN 走 prefill(prev + Δ_prev),其它账户 = 上期末
            BigDecimal prefillBalance = computePrefillBalance(period, account, todo, systemMemberId);
            todo.setPrefilledBalance(prefillBalance);
            snapshotTodoMapper.insert(todo);

            // 同时写入 period_snapshot,使每个账户开账即"已平衡 ✓"(用户后续只需调整变化的账户)
            // upsert + 仅当目标 snapshot 不存在时写入(idempotent)
            if (prefillBalance != null
                    && snapshotMapper.findByPeriodAndAccount(period.getId(), account.getId()).isEmpty()) {
                snapshotMapper.upsert(PeriodSnapshot.builder()
                        .periodId(period.getId())
                        .accountId(account.getId())
                        .endBalance(prefillBalance)
                        .submittedBy(systemMemberId)
                        .note("开账自动延续上期末余额 " + prefillBalance)
                        .build());
            }
        }
    }

    /** 返回当前账户的预填余额:LOAN = prev + (prev - prevPrev),其它 = prev。无历史快照时返回 null(首期开通)。 */
    private BigDecimal computePrefillBalance(Period period, Account account, SnapshotTodo todo, Long systemMemberId) {
        if (account.getType() == AccountType.LOAN) {
            applyLoanPrefill(period, account, todo, systemMemberId);
            return todo.getPrefilledBalance();
        }
        return snapshotMapper.findLatestBefore(account.getId(), period.getPeriodStart(), 1)
                .stream().findFirst()
                .map(PeriodSnapshot::getEndBalance)
                .orElse(null);
    }

    private void applyLoanPrefill(Period period, Account loan, SnapshotTodo todo, Long systemMemberId) {
        List<PeriodSnapshot> previous = snapshotMapper.findLatestBefore(loan.getId(), period.getPeriodStart(), 2);
        if (previous.isEmpty()) {
            return;
        }
        BigDecimal prev = previous.get(0).getEndBalance();
        BigDecimal prevPrev = previous.size() >= 2 ? previous.get(1).getEndBalance() : null;
        BigDecimal predicted = predictLoanBalance(prev, prevPrev);
        todo.setPrefilledBalance(predicted);

        // 草稿还款额 = 本期预测的实际还款额(predicted − prev,>0 才是"还款使欠款减少");
        //   夹零后若已还平(predicted==prev==0)则为 0,不再给已还清的贷款起草还款。
        BigDecimal repay = predicted.subtract(prev);
        if (systemMemberId != null
                && loan.getDefaultPaymentSourceAccountId() != null
                && repay.signum() > 0) {
            Transfer draft = Transfer.builder()
                    .periodId(period.getId())
                    .fromAccountId(loan.getDefaultPaymentSourceAccountId())
                    .toAccountId(loan.getId())
                    .amount(repay)
                    .occurredAt(period.getPeriodEnd())
                    .note("系统根据上期贷款变化预填")
                    .submittedBy(systemMemberId)
                    .draft(true)
                    .build();
            transferMapper.insert(draft);
            todo.setPrefilledTransferId(draft.getId());
        }
    }

    /**
     * v0.11.2 · LOAN 预填余额:趋势外推 prev + (prev − prevPrev),但**夹到 ≤ 0**(贷款=欠款,不为正)。
     *   prevPrev 为 null(仅一期历史)→ 直接沿用 prev。修 bug2:-72000 → 0 后外推成 +72000 的错。
     *   例:房贷 -990000←-1000000 → 外推 -980000(不夹);还平 0←-72000 → 外推 +72000 → 夹到 0。
     */
    static BigDecimal predictLoanBalance(BigDecimal prev, BigDecimal prevPrev) {
        BigDecimal predicted = (prevPrev == null) ? prev : prev.add(prev.subtract(prevPrev));
        return predicted.signum() > 0 ? BigDecimal.ZERO : predicted;
    }
}
