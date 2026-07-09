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

        when(hm.findActiveByAccount(ACC)).thenReturn(List.of(synced_aapl, synced_old, manual, synced_cash));

        BrokerDtos.Snapshot snap = new BrokerDtos.Snapshot(
                List.of(new BrokerDtos.Position("US", "AAPL", BigDecimal.valueOf(20), BigDecimal.valueOf(95), "USD", true),
                        new BrokerDtos.Position("US", "NVDA", BigDecimal.valueOf(5),  BigDecimal.valueOf(92), "USD", true)),
                List.of(new BrokerDtos.Cash("USD", BigDecimal.valueOf(12300)),
                        new BrokerDtos.Cash("HKD", BigDecimal.valueOf(8600))),
                2 /* skippedNonEquity */);

        String summary = svc(hm).reconcile(ACC, BrokerVendor.FUTU, snap);

        // 新增:NVDA + HKD 现金 = 2;更新:AAPL + USD 现金 = 2;归档:OLD = 1
        verify(hm, times(2)).insert(any());
        verify(hm).update(synced_aapl);
        verify(hm).update(synced_cash);
        verify(hm).archive(2L);
        // 关键护栏:用户手填持仓(id=3)绝不被归档/更新
        verify(hm, never()).archive(3L);
        assertThat(summary).contains("新增 2").contains("更新 2").contains("归档 1").contains("跳过期权/期货 2");
        // AAPL 更新为券商新股数/成本
        assertThat(synced_aapl.getShares()).isEqualByComparingTo("20");
        assertThat(synced_aapl.getCostBasis()).isEqualByComparingTo("95");
    }

    @Test
    void reconcile_ignores_rows_from_other_vendor() {
        StockHoldingMapper hm = mock(StockHoldingMapper.class);
        // 该账户只有一条 TIGER 同步行,现在跑 FUTU 对账 → 不应碰它
        StockHolding tigerRow = h(9, ValuationMode.AUTO, "AAPL", Market.US, "USD", "TIGER");
        when(hm.findActiveByAccount(ACC)).thenReturn(List.of(tigerRow));

        BrokerDtos.Snapshot empty = new BrokerDtos.Snapshot(List.of(), List.of(), 0);
        svc(hm).reconcile(ACC, BrokerVendor.FUTU, empty);

        verify(hm, never()).archive(9L);
        verify(hm, never()).update(any());
    }
}
