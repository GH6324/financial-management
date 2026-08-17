package com.family.finance.service;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.member.Member;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodStatus;
import com.family.finance.domain.period.PeriodType;
import com.family.finance.domain.snapshot.PeriodSnapshot;
import com.family.finance.domain.snapshot.SnapshotTodo;
import com.family.finance.domain.snapshot.TodoStatus;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.MemberMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.SnapshotMapper;
import com.family.finance.repository.SnapshotTodoMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v1.16 FR-390(issue #15)· 「有本期数字」和「已填」必须是同一件事。
 *
 * <p>开账把上期末余额延续成本期快照的那一刻,同一行 {@code snapshot_todo} 就得是 DONE。
 * 在此之前:填报页看「有没有数字」判 ✓、tab 徽标数 {@code status='PENDING'},
 * 同一个方法写出来的两份状态互相打架 —— 页面显示全填好了,徽标还挂着 ·1。</p>
 *
 * <p>另外钉住两条不能一起改坏的:无历史(首期 / 新账户第一期)要保持 PENDING,
 * 以及记名的 {@code done_by_member_id} 必须留 NULL(系统代填,见 {@link EntryLoanPromptTest})。</p>
 */
class PeriodOpenerTodoAlignmentTest {

    private static final long FAMILY_ID = 1L;
    private static final long PERIOD_ID = 100L;
    private static final long ACCOUNT_ID = 7L;
    private static final LocalDate START = LocalDate.of(2026, 8, 1);

    private final PeriodService periodService = mock(PeriodService.class);
    private final AccountMapper accountMapper = mock(AccountMapper.class);
    private final MemberMapper memberMapper = mock(MemberMapper.class);
    private final SnapshotMapper snapshotMapper = mock(SnapshotMapper.class);
    private final SnapshotTodoMapper snapshotTodoMapper = mock(SnapshotTodoMapper.class);

    private final PeriodOpener opener = new PeriodOpener(
            mock(FamilyService.class), periodService, accountMapper, memberMapper,
            mock(PeriodMapper.class), snapshotMapper, snapshotTodoMapper);

    private Family family() {
        return Family.builder().id(FAMILY_ID).name("测试之家").periodType(PeriodType.MONTHLY).build();
    }

    private Period period() {
        return Period.builder().id(PERIOD_ID).familyId(FAMILY_ID).periodType(PeriodType.MONTHLY)
                .periodStart(START).periodEnd(START.plusMonths(1).minusDays(1))
                .status(PeriodStatus.OPEN).build();
    }

    private Account account() {
        return Account.builder().id(ACCOUNT_ID).familyId(FAMILY_ID).displayName("工资卡")
                .type(AccountType.CASH).currency("CNY").primaryOwnerMemberId(3L).build();
    }

    /** 公共桩:一个家庭、一个账户、一个成员、todo 尚不存在、本期还没有快照。 */
    private void wireCommon() {
        when(periodService.openIfAbsent(any(Family.class), eq(START))).thenReturn(period());
        when(accountMapper.findActiveByFamily(FAMILY_ID)).thenReturn(List.of(account()));
        when(memberMapper.findActiveByFamily(FAMILY_ID))
                .thenReturn(List.of(Member.builder().id(3L).familyId(FAMILY_ID).displayName("我").build()));
        when(snapshotTodoMapper.findByPeriodAndAccount(PERIOD_ID, ACCOUNT_ID)).thenReturn(Optional.empty());
        when(snapshotMapper.findByPeriodAndAccount(PERIOD_ID, ACCOUNT_ID)).thenReturn(Optional.empty());
    }

    private void withHistory(String endBalance) {
        when(snapshotMapper.findLatestBefore(eq(ACCOUNT_ID), eq(START), anyInt()))
                .thenReturn(List.of(PeriodSnapshot.builder().accountId(ACCOUNT_ID)
                        .endBalance(new BigDecimal(endBalance)).build()));
    }

    @Test
    void carriesForward_marksTodoDone() {
        wireCommon();
        withHistory("12345.67");

        opener.createPeriodAndTodos(family(), START);

        // 写了本期快照 → 同一行 todo 必须一起标 DONE,否则徽标和页面又对不上
        verify(snapshotMapper).upsert(any(PeriodSnapshot.class));
        verify(snapshotTodoMapper).markCarriedForward(PERIOD_ID, ACCOUNT_ID);
        // 记名走的是「系统代填」那条,不许借 markDone 塞一个人进去(FR-392 靠 NULL 区分)
        verify(snapshotTodoMapper, never()).markDone(anyLong(), anyLong(), anyLong());
    }

    @Test
    void noHistory_staysPending() {
        wireCommon();
        when(snapshotMapper.findLatestBefore(eq(ACCOUNT_ID), eq(START), anyInt())).thenReturn(List.of());

        opener.createPeriodAndTodos(family(), START);

        // 首期 / 新建账户的第一期:没数字可延续 → 不写快照 → 保持 PENDING,该催还是要催
        verify(snapshotMapper, never()).upsert(any(PeriodSnapshot.class));
        verify(snapshotTodoMapper, never()).markCarriedForward(anyLong(), anyLong());
    }

    @Test
    void existingTodo_notTouched() {
        wireCommon();
        withHistory("999");
        when(snapshotTodoMapper.findByPeriodAndAccount(PERIOD_ID, ACCOUNT_ID))
                .thenReturn(Optional.of(SnapshotTodo.builder().id(55L).periodId(PERIOD_ID)
                        .accountId(ACCOUNT_ID).status(TodoStatus.PENDING).build()));

        opener.createPeriodAndTodos(family(), START);

        // 幂等重跑不许碰已存在的 todo —— 人工把它改回 PENDING 是有意的,开账逻辑不该覆盖
        verify(snapshotTodoMapper, never()).insert(any(SnapshotTodo.class));
        verify(snapshotTodoMapper, never()).markCarriedForward(anyLong(), anyLong());
        verify(snapshotMapper, never()).upsert(any(PeriodSnapshot.class));
    }

    @Test
    void snapshotWrittenByAnotherPath_stillAligns() {
        wireCommon();
        // 快照已由别的路径落库(比如用户在开账前就填了),延续值算得出但不会重复写
        when(snapshotMapper.findByPeriodAndAccount(PERIOD_ID, ACCOUNT_ID))
                .thenReturn(Optional.of(PeriodSnapshot.builder().periodId(PERIOD_ID)
                        .accountId(ACCOUNT_ID).endBalance(new BigDecimal("500")).build()));
        withHistory("400");

        opener.createPeriodAndTodos(family(), START);

        // 判定看的是「写完之后有没有快照」而不是「有没有延续值」:这里没写,但确实有数字 → 照样对齐
        verify(snapshotMapper, never()).upsert(any(PeriodSnapshot.class));
        verify(snapshotTodoMapper, times(1)).markCarriedForward(PERIOD_ID, ACCOUNT_ID);
    }
}
