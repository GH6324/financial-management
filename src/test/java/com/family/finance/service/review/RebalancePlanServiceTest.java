package com.family.finance.service.review;

import com.family.finance.repository.RebalancePlanMapper;
import com.family.finance.service.config.FamilyConfigService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** v1.2 · 划转核销规则(80% 阈值 · from/to 匹配 · 只核销最早一条 · PENDING 才可核) */
class RebalancePlanServiceTest {

    private static RebalancePlanMapper.Item item(long id, long from, long to, String amount, String status) {
        return new RebalancePlanMapper.Item(id, 1L, from, to, new BigDecimal(amount), null, status, null, "A", "B");
    }

    private RebalancePlanService svc(RebalancePlanMapper mapper) {
        FamilyConfigService cs = mock(FamilyConfigService.class);
        when(cs.getDouble(anyLong(), anyString(), anyDouble())).thenReturn(0.8);
        return new RebalancePlanService(mapper, cs);
    }

    @Test
    void matchAtOrAbove80Pct_marksEarliestPendingOnly() {
        RebalancePlanMapper mapper = mock(RebalancePlanMapper.class);
        when(mapper.findActive(1L)).thenReturn(new RebalancePlanMapper.Plan(1L, 1L, 9L, "ACTIVE"));
        when(mapper.findItems(1L)).thenReturn(List.of(
                item(11L, 100L, 200L, "50000", "PENDING"),
                item(12L, 100L, 200L, "50000", "PENDING")));
        svc(mapper).onTransfer(new RebalancePlanService.TransferCreatedEvent(1L, 77L, 100L, 200L, new BigDecimal("40000")));
        verify(mapper).markExecuted(11L, 77L);          // 40000 ≥ 50000×0.8 → 核销最早一条
        verify(mapper, never()).markExecuted(eq(12L), anyLong());
    }

    @Test
    void below80Pct_orWrongAccounts_noMatch() {
        RebalancePlanMapper mapper = mock(RebalancePlanMapper.class);
        when(mapper.findActive(1L)).thenReturn(new RebalancePlanMapper.Plan(1L, 1L, 9L, "ACTIVE"));
        when(mapper.findItems(1L)).thenReturn(List.of(item(11L, 100L, 200L, "50000", "PENDING")));
        // 金额不足 80%
        svc(mapper).onTransfer(new RebalancePlanService.TransferCreatedEvent(1L, 77L, 100L, 200L, new BigDecimal("39999")));
        verify(mapper, never()).markExecuted(anyLong(), anyLong());
        // from/to 不匹配
        svc(mapper).onTransfer(new RebalancePlanService.TransferCreatedEvent(1L, 78L, 100L, 999L, new BigDecimal("50000")));
        verify(mapper, never()).markExecuted(anyLong(), anyLong());
    }

    @Test
    void executedItemsAreSkipped_andNoPlanIsNoop() {
        RebalancePlanMapper mapper = mock(RebalancePlanMapper.class);
        when(mapper.findActive(1L)).thenReturn(new RebalancePlanMapper.Plan(1L, 1L, 9L, "ACTIVE"));
        when(mapper.findItems(1L)).thenReturn(List.of(item(11L, 100L, 200L, "50000", "EXECUTED")));
        svc(mapper).onTransfer(new RebalancePlanService.TransferCreatedEvent(1L, 77L, 100L, 200L, new BigDecimal("50000")));
        verify(mapper, never()).markExecuted(anyLong(), anyLong());

        RebalancePlanMapper empty = mock(RebalancePlanMapper.class);
        when(empty.findActive(2L)).thenReturn(null);
        svc(empty).onTransfer(new RebalancePlanService.TransferCreatedEvent(2L, 1L, 1L, 2L, BigDecimal.TEN));
        verify(empty, never()).findItems(anyLong());
    }

    @Test
    void planProgressView_countsDoneAndAmounts() {
        RebalancePlanMapper mapper = mock(RebalancePlanMapper.class);
        when(mapper.findActive(1L)).thenReturn(new RebalancePlanMapper.Plan(1L, 1L, 9L, "ACTIVE"));
        when(mapper.findItems(1L)).thenReturn(List.of(
                item(11L, 100L, 200L, "50000", "EXECUTED"),
                item(12L, 100L, 300L, "30000", "PENDING"),
                item(13L, 100L, 400L, "20000", "MANUAL_DONE")));
        var pv = svc(mapper).activePlan(1L);
        assertThat(pv.done()).isEqualTo(2);
        assertThat(pv.total()).isEqualTo(3);
        assertThat(pv.doneAmount()).isEqualByComparingTo("70000");
        assertThat(pv.totalAmount()).isEqualByComparingTo("100000");
    }
}
