package com.family.finance.service.stock;

import com.family.finance.domain.stock.Market;
import com.family.finance.repository.StockHoldingMapper;
import com.family.finance.repository.StockHoldingMapper.TickerMarket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 股票价格拉取定时任务 · v0.3 FR-52a · 决策 24。
 *
 * <p>3 个 @Scheduled cron(Asia/Shanghai 时区):</p>
 * <ul>
 *   <li>美股 06:05(收盘后 · 美东 16:00 = 北京 04:00 夏令时 / 05:00 冬令时,留 1 小时 buffer)</li>
 *   <li>A 股 16:10(15:00 收盘后)</li>
 *   <li>港股 16:30(16:00 收盘后)</li>
 * </ul>
 *
 * <p>由 {@code finance.stock.fetch-enabled=true} 启用 · 默认 false · application.yml 控制。
 * 节假日/周末 → 两源都返回 empty · {@link StockPriceFetcher} 不写快照 · UI 用历史价标"陈旧"。</p>
 */
@Component
@Slf4j
public class StockPriceScheduler {

    private final StockHoldingMapper holdingMapper;
    private final StockPriceFetcher fetcher;
    private final boolean enabled;

    public StockPriceScheduler(StockHoldingMapper holdingMapper,
                               StockPriceFetcher fetcher,
                               @Value("${finance.stock.fetch-enabled:false}") boolean enabled) {
        this.holdingMapper = holdingMapper;
        this.fetcher = fetcher;
        this.enabled = enabled;
        log.info("StockPriceScheduler initialized · enabled={}", enabled);
    }

    /**
     * 美股 · 北京时间 06:05(美东 18:05 = 收盘后) · 每天跑。
     */
    @Scheduled(cron = "0 5 6 * * *", zone = "Asia/Shanghai")
    public void fetchUsStocks() {
        if (!enabled) {
            log.debug("US fetch SKIPPED · disabled");
            return;
        }
        fetchMarket(Market.US);
    }

    /**
     * A 股 · 北京时间 16:10 · 仅工作日(周末 cron 不触发)。
     */
    @Scheduled(cron = "0 10 16 * * MON-FRI", zone = "Asia/Shanghai")
    public void fetchCnStocks() {
        if (!enabled) return;
        fetchMarket(Market.CN);
    }

    /**
     * 港股 · 北京时间 16:30 · 仅工作日。
     */
    @Scheduled(cron = "0 30 16 * * MON-FRI", zone = "Asia/Shanghai")
    public void fetchHkStocks() {
        if (!enabled) return;
        fetchMarket(Market.HK);
    }

    /**
     * 内部统一方法 · 也用作 admin 手动触发入口。
     */
    public int fetchMarket(Market market) {
        List<TickerMarket> tickers = holdingMapper.findDistinctAutoTickersByMarket(market.name());
        if (tickers.isEmpty()) {
            log.info("market={} no AUTO holdings · skip fetch", market);
            return 0;
        }
        List<String> tickerList = tickers.stream().map(TickerMarket::ticker).toList();
        log.info("market={} fetching {} tickers · {}", market, tickerList.size(), tickerList);
        return fetcher.fetchAndPersist(market, tickerList, LocalDate.now());
    }
}
