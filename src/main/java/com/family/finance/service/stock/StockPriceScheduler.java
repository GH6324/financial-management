package com.family.finance.service.stock;

import com.family.finance.domain.stock.Market;
import com.family.finance.repository.StockHoldingMapper;
import com.family.finance.repository.StockHoldingMapper.TickerMarket;
import com.family.finance.service.config.FamilyConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 股票价格拉取定时任务 · v0.3 FR-52a / v0.4.18(详 prd/v0.4.md §22)。
 *
 * <p>v0.4.18 改动:</p>
 * <ul>
 *   <li>{@code @Scheduled} 注解删除 · 改由 {@code DynamicScheduleConfig} 用
 *       {@link org.springframework.scheduling.TaskScheduler} 注册 ·
 *       cron 时段从 DB 读 · 用户管理页改后即重排</li>
 *   <li>{@code enabled} 改为读 {@link FamilyConfigService} · 实时生效不重启</li>
 *   <li>方法 {@code fetchUsStocks() / fetchCnStocks() / fetchHkStocks()}
 *       签名保持(admin 手动触发链路不变)</li>
 * </ul>
 *
 * <p>默认 cron(代码兜底):</p>
 * <ul>
 *   <li>美股 06:05 每天</li>
 *   <li>A 股 16:10 工作日</li>
 *   <li>港股 16:30 工作日</li>
 * </ul>
 */
@Component
@Slf4j
public class StockPriceScheduler {

    /** 单家庭模式 · 见 prd §22.3 类 A */
    private static final long FAMILY_ID = 1L;

    private final StockHoldingMapper holdingMapper;
    private final StockPriceFetcher fetcher;
    private final FamilyConfigService configService;

    public StockPriceScheduler(StockHoldingMapper holdingMapper,
                               StockPriceFetcher fetcher,
                               FamilyConfigService configService) {
        this.holdingMapper = holdingMapper;
        this.fetcher = fetcher;
        this.configService = configService;
        log.info("StockPriceScheduler initialized · enabled-source=DB(family_runtime_config.stock_fetch_enabled)");
    }

    /** 实时读 DB 开关 · 用户在 /admin/integrations 改了立刻生效 */
    private boolean isEnabled() {
        return configService.getBoolean(FAMILY_ID, FamilyConfigService.K_STOCK_ENABLED, false);
    }

    /** 美股 · 由 DynamicScheduleConfig 按 cron(stock_cron_us)调用 · 默认每天 06:05 */
    public void fetchUsStocks() {
        if (!isEnabled()) {
            log.debug("US fetch SKIPPED · disabled");
            return;
        }
        fetchMarket(Market.US);
    }

    /** A 股 · 默认工作日 16:10 · cron 由 stock_cron_cn 配 */
    public void fetchCnStocks() {
        if (!isEnabled()) return;
        fetchMarket(Market.CN);
    }

    /** 港股 · 默认工作日 16:30 · cron 由 stock_cron_hk 配 */
    public void fetchHkStocks() {
        if (!isEnabled()) return;
        fetchMarket(Market.HK);
    }

    /**
     * 内部统一方法 · 也用作 admin 手动触发入口(不读 enabled 开关 · 手动等于显式覆盖)。
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
