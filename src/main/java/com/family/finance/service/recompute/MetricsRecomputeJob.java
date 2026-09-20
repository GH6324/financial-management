package com.family.finance.service.recompute;

import com.family.finance.calc.IdentityVerifier;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.period.Period;
import com.family.finance.factview.FactFilter;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.factview.TrendPoint;
import com.family.finance.factview.WaterfallSegment;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.MetricsRecomputeLogMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class MetricsRecomputeJob {
    private final PeriodMapper periodMapper;
    private final FamilyMapper familyMapper;
    private final FactViewService factViewService;
    private final AuditLogService auditLogService;
    private final MetricsRecomputeLogMapper metricsLogMapper;

    @Async
    public void run(long familyId, long periodId) {
        Period period = periodMapper.findById(familyId, periodId).orElse(null);
        if (period == null) {
            return;
        }
        LocalDateTime startedAt = LocalDateTime.now();
        long startNanos = System.nanoTime();
        try {
            Family family = familyMapper.findById(period.getFamilyId())
                    .orElseThrow(() -> new IllegalStateException("家庭不存在: " + period.getFamilyId()));
            Period previous = periodMapper.findLatest(period.getFamilyId(), 240).stream()
                    .filter(candidate -> candidate.getPeriodStart().isBefore(period.getPeriodStart()))
                    .max(Comparator.comparing(Period::getPeriodStart))
                    .orElse(null);
            if (previous == null) {
                auditLogService.record(period.getFamilyId(), null, AuditLogType.SYSTEM,
                        "period", periodId, "指标重算跳过: 无上一周期可校验");
                return;
            }
            FactSlice slice = factViewService.load(new FactFilter(
                    period.getFamilyId(),
                    period.getPeriodType(),
                    previous.getPeriodStart(),
                    period.getPeriodStart(),
                    false,
                    null,
                    family.getBaseCurrency()
            ));
            TrendPoint current = factViewService.netWorthTrend(slice).stream()
                    .filter(point -> Objects.equals(point.periodId(), periodId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("缺少本期净资产 fact row"));
            TrendPoint prev = factViewService.netWorthTrend(slice).stream()
                    .filter(point -> Objects.equals(point.periodId(), previous.getId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("缺少上期净资产 fact row"));
            WaterfallSegment waterfall = factViewService.incomeExpenseWaterfall(slice).stream()
                    .filter(segment -> Objects.equals(segment.periodId(), periodId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("缺少本期瀑布 fact row"));
            IdentityVerifier.assertMain(
                    current.value(),
                    prev.value(),
                    waterfall.income(),
                    waterfall.expense(),
                    waterfall.pnl()
            );
            int durationMs = (int) ((System.nanoTime() - startNanos) / 1_000_000);
            metricsLogMapper.insert(period.getFamilyId(), periodId, startedAt, LocalDateTime.now(),
                    durationMs, true, BigDecimal.ZERO, null);
            auditLogService.record(period.getFamilyId(), null, AuditLogType.METRICS_RECOMPUTE,
                    "period", periodId, "指标重算完成: 主恒等式通过 · " + durationMs + " ms");
        } catch (Exception ex) {
            int durationMs = (int) ((System.nanoTime() - startNanos) / 1_000_000);
            metricsLogMapper.insert(period.getFamilyId(), periodId, startedAt, LocalDateTime.now(),
                    durationMs, false, null, ex.getMessage());
            auditLogService.record(period.getFamilyId(), null, AuditLogType.METRICS_RECOMPUTE,
                    "period", periodId, "指标重算失败: " + ex.getMessage());
        }
    }
}
