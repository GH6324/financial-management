package com.family.finance.domain.stock;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 股票价格快照 · v0.3 FR-52a。
 *
 * <p>每日 cron 拉取的股价缓存。复合主键 (ticker, market, trade_date)。
 * 拉价失败时,fallback 到本表最近行(UI 标"价格陈旧 N 天")。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockPriceSnapshot {
    private String ticker;
    private Market market;
    private LocalDate tradeDate;
    private BigDecimal closePrice;
    private String currency;
    private String source;
    private LocalDateTime fetchedAt;
}
