package com.family.finance.service.stock;

import com.family.finance.domain.stock.Market;
import com.family.finance.repository.StockPriceSnapshotMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * v0.12+crypto · StockPriceFetcher 的加密货币三源 fallback 编排:
 * Binance(主)→ CoinGecko → Coinbase,只对上一源缺失的 ticker 继续兜底,全部落库。
 * (作者已覆盖各 client 的 parse / 估值 / 调度;此处补"编排"这一层。)
 */
class StockPriceFetcherCryptoTest {

    private BinanceCryptoClient binance;
    private CoinGeckoCryptoClient coinGecko;
    private CoinbaseCryptoClient coinbase;
    private StockPriceSnapshotMapper snapshotMapper;
    private StockPriceFetcher fetcher;

    private static StockQuote q(String t, String price) {
        return new StockQuote(t, Market.CRYPTO, new BigDecimal(price), "USD", "test");
    }

    @BeforeEach
    void setUp() {
        binance = mock(BinanceCryptoClient.class);
        coinGecko = mock(CoinGeckoCryptoClient.class);
        coinbase = mock(CoinbaseCryptoClient.class);
        snapshotMapper = mock(StockPriceSnapshotMapper.class);
        fetcher = new StockPriceFetcher(
                mock(SinaStockClient.class), mock(TencentStockClient.class),
                coinGecko, binance, coinbase, snapshotMapper);
    }

    @Test
    void cryptoFallbackChain_binanceThenCoinGeckoThenCoinbase() {
        List<String> tickers = List.of("BTC", "ETH", "DOGE");
        when(binance.fetchBatch(eq(Market.CRYPTO), any())).thenReturn(Map.of("BTC", q("BTC", "60000")));
        when(coinGecko.fetchBatch(eq(Market.CRYPTO), any())).thenReturn(Map.of("ETH", q("ETH", "3000")));
        when(coinbase.fetchBatch(eq(Market.CRYPTO), any())).thenReturn(Map.of("DOGE", q("DOGE", "0.15")));

        int persisted = fetcher.fetchAndPersist(Market.CRYPTO, tickers, LocalDate.now());

        assertThat(persisted).isEqualTo(3);                 // 三源合起来覆盖 3 个
        verify(snapshotMapper, times(3)).upsert(any());     // 每个都落库
        verify(coinGecko).fetchBatch(eq(Market.CRYPTO), any());
        verify(coinbase).fetchBatch(eq(Market.CRYPTO), any());
    }

    @Test
    void cryptoAllFromBinance_skipsFallbacks() {
        List<String> tickers = List.of("BTC", "ETH");
        when(binance.fetchBatch(eq(Market.CRYPTO), any()))
                .thenReturn(Map.of("BTC", q("BTC", "60000"), "ETH", q("ETH", "3000")));

        int persisted = fetcher.fetchAndPersist(Market.CRYPTO, tickers, LocalDate.now());

        assertThat(persisted).isEqualTo(2);
        verify(snapshotMapper, times(2)).upsert(any());
        verify(coinGecko, never()).fetchBatch(any(), any());   // 主源全中 → 不再兜底
        verify(coinbase, never()).fetchBatch(any(), any());
    }

    @Test
    void cryptoAllSourcesFail_persistsNothing() {
        when(binance.fetchBatch(eq(Market.CRYPTO), any())).thenReturn(Map.of());
        when(coinGecko.fetchBatch(eq(Market.CRYPTO), any())).thenReturn(Map.of());
        when(coinbase.fetchBatch(eq(Market.CRYPTO), any())).thenReturn(Map.of());

        int persisted = fetcher.fetchAndPersist(Market.CRYPTO, List.of("BTC"), LocalDate.now());

        assertThat(persisted).isZero();
        verify(snapshotMapper, never()).upsert(any());         // 拉不到价 → 不落脏数据 · 降级用历史快照
    }

    @Test
    void nonCrypto_doesNotHitCryptoClients() {
        // 非 crypto 市场不应触达 crypto 源(路由隔离)
        when(binance.fetchBatch(any(), any())).thenReturn(Map.of());
        fetcher.fetchAndPersist(Market.US, List.of("BABA"), LocalDate.now());
        verify(binance, never()).fetchBatch(any(), any());
        verify(coinGecko, never()).fetchBatch(any(), any());
        verify(coinbase, never()).fetchBatch(any(), any());
    }
}
