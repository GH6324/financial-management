package com.family.finance.service.goal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.family.finance.domain.goal.Goal;
import com.family.finance.domain.goal.GoalParams;
import com.family.finance.domain.goal.GoalType;
import com.family.finance.repository.GoalMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper.FamilyPeriodAggregate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** v0.5 FR-82 防回归 · FIRE 月支出自动派生(PMC · 剔极端 · 空期回退 · FIXED 跳过)。 */
class FireAutoExpenseTest {

    private GoalMapper goalMapper;
    private PeriodMemberCashflowMapper pmcMapper;
    private final ObjectMapper om = new ObjectMapper();
    private GoalService svc;

    @BeforeEach
    void setUp() {
        goalMapper = mock(GoalMapper.class);
        pmcMapper = mock(PeriodMemberCashflowMapper.class);
        svc = new GoalService(goalMapper, om, pmcMapper);
    }

    private Goal autoGoal(String smoothing) throws Exception {
        GoalParams p = GoalParams.builder()
                .currentAge(35).retireAge(60)
                .monthlyExpense(new BigDecimal("15000"))   // 旧值 · 应被派生覆盖
                .inflationRate(new BigDecimal("0.025")).withdrawalRate(new BigDecimal("0.04"))
                .expenseMode("AUTO_MONTHLY").expenseSmoothing(smoothing).expenseWindowMonths(12)
                .build();
        return Goal.builder().id(1L).familyId(1L).goalType(GoalType.RETIREMENT)
                .paramsJson(om.writeValueAsString(p)).build();
    }

    private FamilyPeriodAggregate agg(long pid, String expense) {
        return new FamilyPeriodAggregate(pid, LocalDate.of(2025, 1, 1), new BigDecimal("45000"), new BigDecimal(expense), 2);
    }

    @Test
    void trimmedDerivesExcludingExtremes() throws Exception {
        when(goalMapper.findActiveByFamilyAndType(1L, GoalType.RETIREMENT)).thenReturn(List.of(autoGoal("TRIMMED")));
        // [20000,22000,50000,21000,23000] · 剔头尾 → mean(21000,22000,23000)=22000
        when(pmcMapper.findFamilyAggregateRecent(eq(1L), anyInt())).thenReturn(List.of(
                agg(1, "20000"), agg(2, "22000"), agg(3, "50000"), agg(4, "21000"), agg(5, "23000")));

        svc.recomputeAutoExpenseGoals(1L);

        ArgumentCaptor<Goal> cap = ArgumentCaptor.forClass(Goal.class);
        verify(goalMapper).update(cap.capture());
        GoalParams updated = om.readValue(cap.getValue().getParamsJson(), GoalParams.class);
        assertThat(updated.getMonthlyExpense()).isEqualByComparingTo("22000.00");  // 剔掉 50000/20000
        assertThat(updated.getExpenseComputedAt()).isNotBlank();
        // targetValue 重算(非空)
        assertThat(cap.getValue().getTargetValue()).isNotNull();
    }

    @Test
    void emptyPmcKeepsOriginal() throws Exception {
        when(goalMapper.findActiveByFamilyAndType(1L, GoalType.RETIREMENT)).thenReturn(List.of(autoGoal("TRIMMED")));
        when(pmcMapper.findFamilyAggregateRecent(eq(1L), anyInt())).thenReturn(List.of());

        svc.recomputeAutoExpenseGoals(1L);

        verify(goalMapper, never()).update(any());  // 数据不足 → 不动
    }

    @Test
    void fixedModeSkipped() throws Exception {
        GoalParams p = GoalParams.builder().currentAge(35).retireAge(60)
                .monthlyExpense(new BigDecimal("15000")).inflationRate(new BigDecimal("0.025"))
                .withdrawalRate(new BigDecimal("0.04")).expenseMode("FIXED").build();
        Goal g = Goal.builder().id(1L).familyId(1L).goalType(GoalType.RETIREMENT)
                .paramsJson(om.writeValueAsString(p)).build();
        when(goalMapper.findActiveByFamilyAndType(1L, GoalType.RETIREMENT)).thenReturn(List.of(g));

        svc.recomputeAutoExpenseGoals(1L);

        verify(goalMapper, never()).update(any());
        verify(pmcMapper, never()).findFamilyAggregateRecent(anyLong(), anyInt());
    }

    @Test
    void medianMode() throws Exception {
        when(goalMapper.findActiveByFamilyAndType(1L, GoalType.RETIREMENT)).thenReturn(List.of(autoGoal("MEDIAN")));
        when(pmcMapper.findFamilyAggregateRecent(eq(1L), anyInt())).thenReturn(List.of(
                agg(1, "20000"), agg(2, "22000"), agg(3, "50000")));  // 中位 22000

        svc.recomputeAutoExpenseGoals(1L);

        ArgumentCaptor<Goal> cap = ArgumentCaptor.forClass(Goal.class);
        verify(goalMapper).update(cap.capture());
        GoalParams updated = om.readValue(cap.getValue().getParamsJson(), GoalParams.class);
        assertThat(updated.getMonthlyExpense()).isEqualByComparingTo("22000.00");
    }
}
