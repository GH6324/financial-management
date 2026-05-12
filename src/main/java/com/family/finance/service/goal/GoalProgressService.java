package com.family.finance.service.goal;

import com.family.finance.calc.GoalProgressCalculator;
import com.family.finance.calc.GoalProjector;
import com.family.finance.domain.goal.Goal;
import com.family.finance.domain.goal.GoalParams;
import com.family.finance.domain.goal.GoalType;
import com.family.finance.domain.period.Period;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.factview.KpiSnapshot;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper.FamilyPeriodAggregate;
import com.family.finance.repository.SnapshotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 目标进度 + 三情景预测组合服务 · v0.3 FR-50。
 *
 * <p>v0.3 修订(2026-05-13):月度收支改为成员级 {@link PeriodMemberCashflowMapper}。
 * 月供 / 应急基线由家庭聚合(SUM 跨成员)计算。</p>
 */
@Service
@RequiredArgsConstructor
public class GoalProgressService {

    private static final int CONTRIBUTION_LOOKBACK_PERIODS = 6;
    private static final int EMERGENCY_BASELINE_LOOKBACK = 3;
    private static final int PROJECTION_YEARS = 30;

    private final GoalService goalService;
    private final FactViewService factViewService;
    private final PeriodMapper periodMapper;
    private final AccountMapper accountMapper;
    private final SnapshotMapper snapshotMapper;
    private final PeriodMemberCashflowMapper memberCashflowMapper;

    public GoalProgress compute(long familyId, Goal goal) {
        GoalParams params = goalService.parseParams(goal);
        BigDecimal pv = computePv(familyId, goal.getGoalType());
        BigDecimal autoBaseline = (goal.getGoalType() == GoalType.EMERGENCY)
            ? computeEmergencyAutoBaseline(familyId) : null;
        BigDecimal target = GoalProgressCalculator.computeTarget(goal.getGoalType(), params, autoBaseline);
        BigDecimal progress = GoalProgressCalculator.computeProgress(pv, target);
        BigDecimal monthlyContribution = computeMonthlyContributionMedian(familyId);
        GoalProjector.ScenarioResult scenarios = GoalProjector.project(
            pv, monthlyContribution, target, PROJECTION_YEARS);
        return new GoalProgress(goal, params, pv, target, progress, monthlyContribution, scenarios);
    }

    public List<GoalProgress> computeAll(long familyId) {
        List<Goal> goals = goalService.findActiveByFamily(familyId);
        List<GoalProgress> out = new ArrayList<>(goals.size());
        for (Goal g : goals) out.add(compute(familyId, g));
        return out;
    }

    // ---------- PV 计算 ----------

    public BigDecimal computePv(long familyId, GoalType type) {
        if (type == GoalType.EMERGENCY) return computeCashPv(familyId);
        FactSlice slice = factViewService.loadDefault(familyId);
        KpiSnapshot kpi = factViewService.kpis(slice);
        return kpi.netWorth() == null ? BigDecimal.ZERO : kpi.netWorth();
    }

    private BigDecimal computeCashPv(long familyId) {
        Period current = periodMapper.findCurrentOpen(familyId).orElse(null);
        if (current == null) {
            List<Period> latest = periodMapper.findLatest(familyId, 1);
            if (latest.isEmpty()) return BigDecimal.ZERO;
            current = latest.get(0);
        }
        return snapshotMapper.sumEndBalanceByAccountType(familyId, current.getId(), "CASH")
            .orElse(BigDecimal.ZERO);
    }

    // ---------- 月度供款中位数 ----------
    // v0.3 修订:用 period_member_cashflow 家庭聚合(SUM 跨成员)再算中位

    public BigDecimal computeMonthlyContributionMedian(long familyId) {
        List<FamilyPeriodAggregate> recent = memberCashflowMapper
            .findFamilyAggregateRecent(familyId, CONTRIBUTION_LOOKBACK_PERIODS);
        List<BigDecimal> savings = recent.stream()
            .map(a -> a.totalIncome().subtract(a.totalExpense()))
            .toList();
        return GoalProgressCalculator.medianMonthlyContribution(savings);
    }

    /**
     * 应急 auto_baseline = 过去 3 期 SUM(member total_expense_input) 的中位。
     */
    public BigDecimal computeEmergencyAutoBaseline(long familyId) {
        List<FamilyPeriodAggregate> recent = memberCashflowMapper
            .findFamilyAggregateRecent(familyId, EMERGENCY_BASELINE_LOOKBACK);
        if (recent.isEmpty()) return null;
        List<BigDecimal> expenses = recent.stream().map(FamilyPeriodAggregate::totalExpense).toList();
        return GoalProgressCalculator.medianMonthlyContribution(expenses);
    }

    // ---------- VO ----------

    public record GoalProgress(
        Goal goal,
        GoalParams params,
        BigDecimal pv,
        BigDecimal target,
        BigDecimal progress,
        BigDecimal monthlyContribution,
        GoalProjector.ScenarioResult scenarios
    ) {
        public boolean targetReached() {
            return target != null && pv != null && target.signum() > 0 && pv.compareTo(target) >= 0;
        }
        public BigDecimal progressPct() {
            return progress.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_EVEN);
        }
        public LocalDate neutralDate() {
            return scenarios == null ? null : scenarios.neutralDate();
        }
    }
}
