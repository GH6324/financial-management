package com.family.finance.service.stock;

import com.family.finance.domain.stock.Market;
import com.family.finance.domain.stock.StockPriceSnapshot;
import com.family.finance.repository.StockPriceSnapshotMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 双源拉价 + 圆形熔断 · v0.3 FR-52a · 决策 24。
 *
 * <p>策略:</p>
 * <ol>
 *   <li>新浪连续 3 次失败 → 跳过新浪 5 分钟 · 直接走腾讯</li>
 *   <li>新浪 / 腾讯都返回结果 → 优先用新浪(主)· 缺的 ticker 用腾讯补</li>
 *   <li>都失败 → 上游自己用 stock_price_snapshot 最近行兜底</li>
 *   <li>5 分钟后下次调用自动探测新浪 · 若成功 → 重置 consecutiveFailures · 关闭熔断</li>
 * </ol>
 *
 * <p>沿用 v0.2 QwenLlmClient 的熔断模式(inline · volatile · 不抽公共类避免影响 v0.2 行为)。</p>
 */
@Service
@Slf4j
public class StockPriceFetcher {

    private static final int FAILURE_THRESHOLD = 3;
    private static final Duration BREAKER_OPEN_DURATION = Duration.ofMinutes(5);

    private final SinaStockClient sinaClient;
    private final TencentStockClient tencentClient;
    private final StockPriceSnapshotMapper snapshotMapper;

    private volatile int sinaConsecutiveFailures = 0;
    private volatile Instant sinaBreakerOpenedAt = Instant.EPOCH;

    public StockPriceFetcher(SinaStockClient sinaClient,
                             TencentStockClient tencentClient,
                             StockPriceSnapshotMapper snapshotMapper) {
        this.sinaClient = sinaClient;
        this.tencentClient = tencentClient;
        this.snapshotMapper = snapshotMapper;
    }

    /**
     * 拉一组同市场 ticker 的价格 · 写入 stock_price_snapshot · 返回成功条数。
     *
     * @param market   市场
     * @param tickers  ticker 列表(按市场规范化:US 大写 / CN 6 位 / HK 5 位前导零)
     * @param tradeDate 交易日 · 通常 LocalDate.now()
     * @return 成功写入快照的 ticker 数
     */
    public int fetchAndPersist(Market market, List<String> tickers, LocalDate tradeDate) {
        if (tickers == null || tickers.isEmpty()) return 0;

        // 1. 主源(若熔断打开则跳过)
        Map<String, StockQuote> primary = Map.of();
        if (isSinaBreakerClosed()) {
            primary = sinaClient.fetchBatch(market, tickers);
            if (primary.isEmpty()) {
                recordSinaFailure();
            } else {
                resetSinaBreaker();
            }
        } else {
            log.info("sina breaker OPEN · skipping primary · market={}", market);
        }

        // 2. 备源(若主源结果不全)
        Map<String, StockQuote> backup;
        if (primary.size() < tickers.size()) {
            backup = tencentClient.fetchBatch(market, tickers);
        } else {
            backup = Map.of();
        }

        // 3. 优先主源 · 缺的用备源 · 写入快照
        int persisted = 0;
        for (String ticker : tickers) {
            StockQuote q = primary.getOrDefault(ticker, backup.get(ticker));
            if (q != null) {
                StockPriceSnapshot snap = StockPriceSnapshot.builder()
                    .ticker(q.ticker())
                    .market(q.market())
                    .tradeDate(tradeDate)
                    .closePrice(q.closePrice())
                    .currency(q.currency())
                    .source(q.source())
                    .build();
                snapshotMapper.upsert(snap);
                persisted++;
            } else {
                log.warn("price fetch all-source FAIL · market={} ticker={} · downstream uses fallback snapshot",
                    market, ticker);
            }
        }
        log.info("price fetch · market={} requested={} persisted={} sinaBreakerOpen={}",
            market, tickers.size(), persisted, !isSinaBreakerClosed());
        return persisted;
    }

    /**
     * 给单个 ticker 查最新已知价 · 包含今日 + 历史 fallback · 给 AccountValuationService 用。
     */
    public StockPriceSnapshot findLatestKnown(String ticker, Market market) {
        return snapshotMapper.findLatest(ticker, market.name()).orElse(null);
    }

    // ---------- 熔断 ----------

    private boolean isSinaBreakerClosed() {
        if (sinaConsecutiveFailures < FAILURE_THRESHOLD) return true;
        Instant openUntil = sinaBreakerOpenedAt.plus(BREAKER_OPEN_DURATION);
        return Instant.now().isAfter(openUntil);
    }

    private void recordSinaFailure() {
        sinaConsecutiveFailures++;
        if (sinaConsecutiveFailures >= FAILURE_THRESHOLD
                && sinaBreakerOpenedAt.plus(BREAKER_OPEN_DURATION).isBefore(Instant.now())) {
            // 累计阈值 + 上次熔断已超期 → 重新打开熔断窗口
            sinaBreakerOpenedAt = Instant.now();
            log.warn("sina breaker OPENED · consecutiveFailures={}", sinaConsecutiveFailures);
        }
    }

    private void resetSinaBreaker() {
        if (sinaConsecutiveFailures > 0) {
            log.info("sina breaker RESET · was {} failures", sinaConsecutiveFailures);
        }
        sinaConsecutiveFailures = 0;
        sinaBreakerOpenedAt = Instant.EPOCH;
    }

    // ---------- 测试 hook ----------

    /** 供单测断言。 */
    int getSinaConsecutiveFailures() { return sinaConsecutiveFailures; }
    boolean isSinaBreakerOpen() { return !isSinaBreakerClosed(); }
}
