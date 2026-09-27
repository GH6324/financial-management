package com.family.finance.service.broker;

import com.family.finance.domain.broker.BrokerVendor;
import com.family.finance.domain.stock.Market;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.domain.stock.ValuationMode;
import com.family.finance.repository.BrokerLinkMapper;
import com.family.finance.repository.StockHoldingMapper;
import com.family.finance.service.stock.AccountValuationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v0.15 护栏 · 券商对账不变式:
 * <ul>
 *   <li>只动 {@code sync_source=本 vendor} 的行 —— <b>绝不碰用户手填持仓</b>;</li>
 *   <li>券商有我方无 → insert;都有 → update;我方有券商无 → archive;</li>
 *   <li>现金按币种 upsert;期权/期货计数体现在摘要。</li>
 * </ul>
 */
class BrokerReconcileTest {

    private static final long FAM = 1L;
    private static final long ACC = 10L;

    private StockHolding h(long id, ValuationMode mode, String ticker, Market market,
                           String currency, String syncSource) {
        return StockHolding.builder().id(id).accountId(ACC).valuationMode(mode)
                .ticker(ticker).market(market).currency(currency)
                .shares(BigDecimal.ONE).costBasis(BigDecimal.TEN)
                .manualValue(BigDecimal.valueOf(100)).syncSource(syncSource).build();
    }

    private BrokerSyncService svc(StockHoldingMapper holdingMapper) {
        return new BrokerSyncService(mock(BrokerLinkMapper.class), holdingMapper,
                List.of(), mock(AccountValuationService.class));
    }

    @Test
    void reconcile_upserts_and_archives_only_synced_rows() {
        StockHoldingMapper hm = mock(StockHoldingMapper.class);

        StockHolding synced_aapl = h(1, ValuationMode.AUTO, "AAPL", Market.US, "USD", "FUTU"); // 更新
        StockHolding synced_old  = h(2, ValuationMode.AUTO, "OLD",  Market.US, "USD", "FUTU"); // 券商已无 → 归档
        StockHolding manual      = h(3, ValuationMode.MANUAL, null, null, "CNY", null);        // 用户手填 → 不可碰
        StockHolding synced_cash = h(4, ValuationMode.CASH,  null, null, "USD", "FUTU");        // 现金更新

        when(hm.findActiveByAccount(FAM, ACC)).thenReturn(List.of(synced_aapl, synced_old, manual, synced_cash));

        BrokerDtos.Snapshot snap = new BrokerDtos.Snapshot(
                List.of(new BrokerDtos.Position("US", "AAPL", "苹果", BigDecimal.valueOf(20), BigDecimal.valueOf(95), "USD", true),
                        new BrokerDtos.Position("US", "NVDA", "英伟达", BigDecimal.valueOf(5),  BigDecimal.valueOf(92), "USD", true)),
                List.of(new BrokerDtos.Cash("USD", BigDecimal.valueOf(12300)),
                        new BrokerDtos.Cash("HKD", BigDecimal.valueOf(8600))),
                2 /* skippedNonEquity */);

        String summary = svc(hm).reconcile(FAM, ACC, BrokerVendor.FUTU, snap);

        // 新增:NVDA + HKD 现金 = 2;更新:AAPL + USD 现金 = 2;归档:OLD = 1
        verify(hm, times(2)).insertOwned(anyLong(), any());
        verify(hm).update(FAM, synced_aapl);
        verify(hm).update(FAM, synced_cash);
        verify(hm).archive(FAM, 2L);
        // 关键护栏:用户手填持仓(id=3)绝不被归档/更新
        verify(hm, never()).archive(FAM, 3L);
        assertThat(summary).contains("新增 2").contains("更新 2").contains("归档 1").contains("跳过期权/期货 2");
        // AAPL 更新为券商新股数/成本
        assertThat(synced_aapl.getShares()).isEqualByComparingTo("20");
        assertThat(synced_aapl.getCostBasis()).isEqualByComparingTo("95");
        // 显示名升级:旧名是裸代码/空 → 用券商证券名(用户改过的名不覆盖,由 null→苹果 这条路径覆盖)
        assertThat(synced_aapl.getDisplayName()).isEqualTo("苹果");
    }

    @Test
    void reconcile_ignores_rows_from_other_vendor() {
        StockHoldingMapper hm = mock(StockHoldingMapper.class);
        // 该账户只有一条 TIGER 同步行,现在跑 FUTU 对账 → 不应碰它
        StockHolding tigerRow = h(9, ValuationMode.AUTO, "AAPL", Market.US, "USD", "TIGER");
        when(hm.findActiveByAccount(FAM, ACC)).thenReturn(List.of(tigerRow));

        BrokerDtos.Snapshot empty = new BrokerDtos.Snapshot(List.of(), List.of(), 0);
        svc(hm).reconcile(FAM, ACC, BrokerVendor.FUTU, empty);

        verify(hm, never()).archive(FAM, 9L);
        verify(hm, never()).update(anyLong(), any());
    }

    /**
     * v1.26 · IBKR 里拉不到价的市场(伦敦上市的美元 ETF 等)→ 手动估值行,单价 = 报表收盘价折成账户币种。
     * 手动估值行的单价语义是「账户币种」,不折算就会把美元单价当人民币。
     */
    @Test
    void reconcile_ibkr_manual_positions_are_priced_in_account_currency() {
        StockHoldingMapper hm = mock(StockHoldingMapper.class);
        AccountValuationService vs = mock(AccountValuationService.class);
        when(vs.fxToAccountCurrency(FAM, ACC, "USD")).thenReturn(new BigDecimal("7.10"));
        StockHolding oldLse = StockHolding.builder().id(21L).accountId(ACC).valuationMode(ValuationMode.MANUAL)
                .ticker("VWRA").shares(BigDecimal.TEN).manualValue(BigDecimal.ONE).syncSource("IBKR").build();
        when(hm.findActiveByAccount(FAM, ACC)).thenReturn(List.of(oldLse));

        BrokerDtos.Snapshot snap = new BrokerDtos.Snapshot(List.of(), List.of(), 0, List.of(
                new BrokerDtos.ManualPosition("VWRA", "VANGUARD FTSE ALL-WORLD", "LSEETF",
                        new BigDecimal("30"), new BigDecimal("131.52"), new BigDecimal("118.20"), "USD"),
                new BrokerDtos.ManualPosition("7203", "TOYOTA", "TSEJ",
                        new BigDecimal("100"), new BigDecimal("20.00"), null, "USD")));
        String summary = new BrokerSyncService(mock(BrokerLinkMapper.class), hm, List.of(), vs)
                .reconcile(FAM, ACC, BrokerVendor.IBKR, snap);

        // 已有的 VWRA:股数与单价都换成新报表的,单价折成账户币种(131.52 × 7.10)
        verify(hm).update(FAM, oldLse);
        assertThat(oldLse.getShares()).isEqualByComparingTo("30");
        assertThat(oldLse.getManualValue()).isEqualByComparingTo("933.792");
        assertThat(oldLse.getCostBasis()).isEqualByComparingTo("839.22");
        assertThat(oldLse.getManualValueAt()).isNotNull();
        // 新的丰田:新建一条手动估值行,标 IBKR 同步来源
        org.mockito.ArgumentCaptor<StockHolding> cap = org.mockito.ArgumentCaptor.forClass(StockHolding.class);
        verify(hm).insertOwned(org.mockito.ArgumentMatchers.eq(FAM), cap.capture());
        assertThat(cap.getValue().getValuationMode()).isEqualTo(ValuationMode.MANUAL);
        assertThat(cap.getValue().getSyncSource()).isEqualTo("IBKR");
        assertThat(cap.getValue().getDisplayName()).isEqualTo("TOYOTA · TSEJ");
        assertThat(summary).contains("按券商收盘价估值 2");
    }
}
