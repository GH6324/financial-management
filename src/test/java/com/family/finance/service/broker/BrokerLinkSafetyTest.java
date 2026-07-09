package com.family.finance.service.broker;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.domain.broker.BrokerLink;
import com.family.finance.domain.broker.BrokerVendor;
import com.family.finance.domain.stock.Market;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.domain.stock.ValuationMode;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.BrokerLinkMapper;
import com.family.finance.repository.StockHoldingMapper;
import com.family.finance.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v0.15 护栏 · 关联(高危不可自动回退)安全网:
 * <b>关联前必须先留审计快照,再软归档现有持仓,最后才建绑定</b> —— 顺序不能错,
 * 否则"后悔了找回"的承诺落空。
 */
class BrokerLinkSafetyTest {

    @Test
    void link_snapshots_before_archiving_then_binds() {
        AccountMapper am = mock(AccountMapper.class);
        StockHoldingMapper hm = mock(StockHoldingMapper.class);
        BrokerLinkMapper lm = mock(BrokerLinkMapper.class);
        AuditLogService audit = mock(AuditLogService.class);
        BrokerSyncService sync = mock(BrokerSyncService.class);

        Account acc = mock(Account.class);
        when(acc.getFamilyId()).thenReturn(1L);
        when(acc.getType()).thenReturn(AccountType.STOCK);
        when(am.findById(100L)).thenReturn(Optional.of(acc));
        when(lm.findByAccount(100L)).thenReturn(Optional.empty()); // 尚未关联
        StockHolding existing = StockHolding.builder().id(7L).accountId(100L)
                .valuationMode(ValuationMode.MANUAL).ticker("PRIV").market(Market.US)
                .manualValue(BigDecimal.valueOf(5000)).currency("USD").build();
        when(hm.findActiveByAccount(100L)).thenReturn(List.of(existing));
        when(sync.sync(anyLong(), anyLong(), any())).thenReturn("同步 · 新增 1 · 更新 0 · 归档 0");

        BrokerLinkService svc = new BrokerLinkService(lm, hm, am, audit, sync);
        String msg = svc.link(1L, 100L, BrokerVendor.FUTU, "acct-x", 2L);

        // 顺序护栏:审计快照 → 归档 → 建绑定
        InOrder io = inOrder(audit, hm, lm);
        ArgumentCaptor<String> snap = ArgumentCaptor.forClass(String.class);
        io.verify(audit).record(eq(1L), eq(2L), eq(AuditLogType.BROKER_LINK), eq("account"), eq(100L), snap.capture());
        io.verify(hm).archive(7L);
        io.verify(lm).insert(any(BrokerLink.class));

        // 快照带上了归档前的持仓信息(可供找回)
        assertThat(snap.getValue()).contains("PRIV").contains("快照");
        assertThat(msg).contains("已关联");
    }

    @Test
    void link_rejects_non_holding_account() {
        AccountMapper am = mock(AccountMapper.class);
        Account acc = mock(Account.class);
        when(acc.getFamilyId()).thenReturn(1L);
        when(acc.getType()).thenReturn(AccountType.LOAN); // 非持仓类
        when(am.findById(200L)).thenReturn(Optional.of(acc));

        BrokerLinkService svc = new BrokerLinkService(mock(BrokerLinkMapper.class),
                mock(StockHoldingMapper.class), am, mock(AuditLogService.class), mock(BrokerSyncService.class));

        try {
            svc.link(1L, 200L, BrokerVendor.TIGER, null, 2L);
            assertThat(false).as("应拒绝非持仓类账户").isTrue();
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("持仓");
        }
    }

    @Test
    void unlink_clears_sync_source_and_audits() {
        AccountMapper am = mock(AccountMapper.class);
        StockHoldingMapper hm = mock(StockHoldingMapper.class);
        BrokerLinkMapper lm = mock(BrokerLinkMapper.class);
        AuditLogService audit = mock(AuditLogService.class);
        Account acc = mock(Account.class);
        when(acc.getFamilyId()).thenReturn(1L);
        when(am.findById(100L)).thenReturn(Optional.of(acc));

        BrokerLinkService svc = new BrokerLinkService(lm, hm, am, audit, mock(BrokerSyncService.class));
        svc.unlink(1L, 100L, 2L);

        verify(lm).deleteByAccount(100L);
        verify(hm).clearSyncSource(100L);
        verify(audit).record(eq(1L), eq(2L), eq(AuditLogType.BROKER_LINK), eq("account"), eq(100L), any());
    }
}
