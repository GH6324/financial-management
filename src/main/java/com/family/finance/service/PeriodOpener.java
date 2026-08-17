package com.family.finance.service;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.member.Member;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.snapshot.PeriodSnapshot;
import com.family.finance.domain.snapshot.SnapshotTodo;
import com.family.finance.domain.snapshot.TodoStatus;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.MemberMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.SnapshotMapper;
import com.family.finance.repository.SnapshotTodoMapper;
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
            boolean snapshotExists = snapshotMapper
                    .findByPeriodAndAccount(period.getId(), account.getId()).isPresent();
            if (prefillBalance != null && !snapshotExists) {
                snapshotMapper.upsert(PeriodSnapshot.builder()
                        .periodId(period.getId())
                        .accountId(account.getId())
                        .endBalance(prefillBalance)
                        .submittedBy(systemMemberId)
                        .note("开账自动延续上期末余额 " + prefillBalance)
                        .build());
                snapshotExists = true;
            }

            // v1.16 FR-390(issue #15)· 这一行已经有本期数字了,todo 就该是"已填"。
            //   在此之前:填报页看「有没有数字」判 ✓、tab 徽标和自动关账看 status='PENDING' 数「未填」,
            //   同一个方法写出来的两份状态互相打架 —— 页面显示全填好了,徽标还挂着 ·1(issue #15)。
            //   判定用"写完之后有没有快照"而不是"有没有延续值":正常路径两者等价,
            //   但幂等重跑 / 快照由别的路径先落库时,前者才是对的。
            //   无历史(首期 / 新建账户的第一期)→ 没有快照 → 保持 PENDING,那时候确实该催。
            if (snapshotExists) {
                snapshotTodoMapper.markCarriedForward(period.getId(), account.getId());
            }
        }
    }

    /**
     * 返回当前账户的预填余额 = 上月末值(prev)。所有账户一致 carry-forward,无历史快照返回 null(首期)。
     *
     * <p>v0.17.x · 贷款不再<b>静默</b>外推 prev+Δ(旧行为:开账即把趋势预测写进 snapshot + 起草还款转账,
     * 用户不知情)。趋势预测改由填报页<b>贷款行内提示条</b>让用户显式「接受 / 保持上月」
     * (见 {@link EntryService#acceptLoanPrediction} + {@link #predictLoanBalance})。
     * 兼容:{@code createPeriodAndTodos} 幂等(已有 todo 则跳过),只影响将来新开账期,老账期不回改。</p>
     */
    private BigDecimal computePrefillBalance(Period period, Account account, SnapshotTodo todo, Long systemMemberId) {
        return snapshotMapper.findLatestBefore(account.getId(), period.getPeriodStart(), 1)
                .stream().findFirst()
                .map(PeriodSnapshot::getEndBalance)
                .orElse(null);
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
