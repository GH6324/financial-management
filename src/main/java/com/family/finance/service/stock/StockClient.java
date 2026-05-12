package com.family.finance.service.stock;

import com.family.finance.domain.stock.Market;

import java.util.List;
import java.util.Map;

/**
 * 数据源适配器接口 · v0.3 FR-52a。
 *
 * <p>实现类:{@link SinaStockClient}(主) / {@link TencentStockClient}(备)。</p>
 *
 * <p>调用方传一组 (market, ticker) · 拉一批 · 返回 ticker → StockQuote · 解析失败的 ticker 直接缺席不抛异常。</p>
 */
public interface StockClient {

    /** 数据源标识 · 写入 stock_price_snapshot.source */
    String sourceTag();

    /**
     * 批量拉一组 ticker · 同一 market 一次调用。
     *
     * @return ticker → StockQuote · 失败的 ticker 不在 map 中
     */
    Map<String, StockQuote> fetchBatch(Market market, List<String> tickers);

    /** 内部测试用 · 子类暴露解析器供单测验证。 */
    default Map<String, StockQuote> parseResponse(Market market, String body) {
        throw new UnsupportedOperationException("subclass must override for tests");
    }
}
