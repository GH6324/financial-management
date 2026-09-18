package com.family.finance.service;

import com.family.finance.domain.family.Family;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodType;
import com.family.finance.repository.PeriodMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.23 · 关账宽限判据(issue #20)。
 *
 * <p>这一组测试守的是**零差异基线的前提**:T+0 时新判据必须与 v1.22
 * 「开新期时关上期」完全等价。等价不成立的话,存量家庭升级后关账时点会变,
 * 而那是所有指标的落定时刻。</p>
 */
class PeriodGraceTest {

    private static Family family(int delayDays, boolean autoClose) {
        Family f = new Family();
        f.setId(1L);
        f.setPeriodType(PeriodType.MONTHLY);
        f.setCloseDelayDays(delayDays);
        f.setAutoCloseEnabled(autoClose);
        return f;
    }

    private static Period period(long id, LocalDate start, LocalDate end) {
        Period p = new Period();
        p.setId(id);
        p.setFamilyId(1L);
        p.setPeriodType(PeriodType.MONTHLY);
        p.setPeriodStart(start);
        p.setPeriodEnd(end);
        return p;
    }

    private static PeriodService svc(PeriodMapper mapper) {
        return new PeriodService(mapper,
                mock(com.family.finance.repository.MemberMapper.class),
                mock(com.family.finance.repository.SnapshotTodoMapper.class),
                mock(com.family.finance.repository.PeriodMemberCompletionMapper.class),
                mock(com.family.finance.repository.PeriodReopenLogMapper.class),
                mock(com.family.finance.repository.SnapshotMapper.class),
                mock(com.family.finance.repository.PeriodAccountAttrMapper.class),
                mock(com.family.finance.repository.ReviewAiCacheMapper.class),
                mock(com.family.finance.repository.PeriodAccountGroupMapper.class),
                mock(AuditLogService.class),
                mock(com.family.finance.service.recompute.MetricsRecomputeJob.class));
    }

    private static final Period AUG = period(80L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

    // ────────────────────── 截止日 ──────────────────────

    @Test
    @DisplayName("T+0 的截止日就是自然期末 —— 这是与 v1.22 等价的根据")
    void graceDeadline_t0_isPeriodEnd() {
        assertThat(svc(mock(PeriodMapper.class)).graceDeadline(family(0, true), AUG))
                .isEqualTo(LocalDate.of(2026, 8, 31));
    }

    @Test
    @DisplayName("T+2 的截止日是 9/2(月底过完再给 2 天)")
    void graceDeadline_t2() {
        assertThat(svc(mock(PeriodMapper.class)).graceDeadline(family(2, true), AUG))
                .isEqualTo(LocalDate.of(2026, 9, 2));
    }

    // ────────────────────── 该不该关 ──────────────────────

    @Test
    @DisplayName("T+0:新期第一天(9/1)就该关上期 —— 与 v1.22「开新期时关上期」逐日等价")
    void shouldAutoClose_t0_closesOnFirstDayOfNextPeriod() {
        PeriodService s = svc(mock(PeriodMapper.class));
        assertThat(s.shouldAutoClose(family(0, true), AUG, LocalDate.of(2026, 8, 31))).isFalse();
        assertThat(s.shouldAutoClose(family(0, true), AUG, LocalDate.of(2026, 9, 1))).isTrue();
    }

    @Test
    @DisplayName("T+2:截止日当天(9/2)还能填,9/3 才关")
    void shouldAutoClose_t2_deadlineDayStillWritable() {
        PeriodService s = svc(mock(PeriodMapper.class));
        Family f = family(2, true);
        assertThat(s.shouldAutoClose(f, AUG, LocalDate.of(2026, 9, 1))).isFalse();
        assertThat(s.shouldAutoClose(f, AUG, LocalDate.of(2026, 9, 2))).isFalse();   // 「给 2 天」= 9/1、9/2 都能填
        assertThat(s.shouldAutoClose(f, AUG, LocalDate.of(2026, 9, 3))).isTrue();
    }

    @Test
    @DisplayName("手动模式:自动判据永远不成立(平时不关)")
    void shouldAutoClose_manual_neverAuto() {
        PeriodService s = svc(mock(PeriodMapper.class));
        assertThat(s.shouldAutoClose(family(0, false), AUG, LocalDate.of(2026, 12, 31))).isFalse();
    }

    // ────────────────────── 手动模式的兜底 ──────────────────────

    @Test
    @DisplayName("手动模式兜底:还在最近两期里 → 不强制关(留一期缓冲)")
    void mustForceClose_withinRecentTwo_false() {
        PeriodMapper m = mock(PeriodMapper.class);
        when(m.findLatest(anyLong(), anyInt())).thenReturn(List.of(
                period(90L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)), AUG));
        assertThat(svc(m).mustForceCloseOverdue(1L, AUG)).isFalse();
    }

    @Test
    @DisplayName("手动模式兜底:下下期一开,上上期必须关 —— 否则报表永远没有可锚的快照")
    void mustForceClose_pushedOut_true() {
        PeriodMapper m = mock(PeriodMapper.class);
        when(m.findLatest(anyLong(), anyInt())).thenReturn(List.of(
                period(100L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)),
                period(90L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))));
        assertThat(svc(m).mustForceCloseOverdue(1L, AUG)).isTrue();
    }

    @Test
    @DisplayName("兜底判据走账期序而不是日期算术 —— 跨年也不会算错")
    void mustForceClose_acrossYearBoundary() {
        PeriodMapper m = mock(PeriodMapper.class);
        Period dec = period(1200L, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31));
        when(m.findLatest(anyLong(), anyInt())).thenReturn(List.of(
                period(1260L, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31)), dec));
        // 12 月还在最近两期里 → 不关。日期算术("往前数两个月")在跨年处最容易写错,这里用 id 列表所以不会。
        assertThat(svc(m).mustForceCloseOverdue(1L, dec)).isFalse();
    }

    // ────────────────────── 补录期识别 ──────────────────────

    @Test
    @DisplayName("只有一期 OPEN 时没有补录期 —— T+0 家庭永远走不到双活跃分支")
    void findBackfill_singleOpen_empty() {
        PeriodMapper m = mock(PeriodMapper.class);
        when(m.findRecordableOpen(1L)).thenReturn(List.of(
                period(90L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))));
        assertThat(svc(m).findBackfillPeriod(1L, LocalDate.of(2026, 9, 2))).isEmpty();
    }

    @Test
    @DisplayName("双活跃:补录期 = 已自然结束的那一期(不是靠 id 大小或顺序猜)")
    void findBackfill_dualOpen_picksEndedOne() {
        PeriodMapper m = mock(PeriodMapper.class);
        when(m.findRecordableOpen(1L)).thenReturn(List.of(
                AUG, period(90L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))));
        assertThat(svc(m).findBackfillPeriod(1L, LocalDate.of(2026, 9, 2)))
                .isPresent()
                .get()
                .extracting(Period::getId)
                .isEqualTo(80L);
    }

    @Test
    @DisplayName("剩余天数:截止日当天是 0(今天还能填完),过期为负")
    void graceDaysLeft() {
        PeriodService s = svc(mock(PeriodMapper.class));
        Family f = family(2, true);
        assertThat(s.graceDaysLeft(f, AUG, LocalDate.of(2026, 9, 1))).isEqualTo(1);
        assertThat(s.graceDaysLeft(f, AUG, LocalDate.of(2026, 9, 2))).isEqualTo(0);
        assertThat(s.graceDaysLeft(f, AUG, LocalDate.of(2026, 9, 4))).isEqualTo(-2);
    }

    // ────────────────────── 兼容 ──────────────────────

    @Test
    @DisplayName("老数据(两列为 null)按 T+0 + 自动关账算 —— 迁移前后行为不变")
    void nullColumns_behaveLikeLegacy() {
        Family legacy = new Family();
        legacy.setId(1L);
        legacy.setPeriodType(PeriodType.MONTHLY);
        // closeDelayDays / autoCloseEnabled 都是 null
        PeriodService s = svc(mock(PeriodMapper.class));
        assertThat(legacy.closeDelayDaysOrZero()).isZero();
        assertThat(legacy.autoCloseOrDefault()).isTrue();
        assertThat(s.graceDeadline(legacy, AUG)).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(s.shouldAutoClose(legacy, AUG, LocalDate.of(2026, 9, 1))).isTrue();
    }
}
