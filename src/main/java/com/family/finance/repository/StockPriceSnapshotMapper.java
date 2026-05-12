package com.family.finance.repository;

import com.family.finance.domain.stock.StockPriceSnapshot;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * stock_price_snapshot 表 Mapper · v0.3 FR-52a。
 *
 * <p>upsert 用 INSERT ... ON DUPLICATE KEY UPDATE(MySQL 8 语法 · 主键复合)
 * 适应"同一交易日多次拉价"的场景(主源失败后切备源会触发重写)。</p>
 */
@Mapper
public interface StockPriceSnapshotMapper {

    @Select("""
            SELECT ticker, market, trade_date, close_price, currency, source, fetched_at
              FROM stock_price_snapshot
             WHERE ticker = #{ticker}
               AND market = #{market}
               AND trade_date = #{tradeDate}
            """)
    Optional<StockPriceSnapshot> findByPk(@Param("ticker") String ticker,
                                          @Param("market") String market,
                                          @Param("tradeDate") LocalDate tradeDate);

    /**
     * 最新一行(给降级链路用 · 找不到最新价时退到这里 + UI 标"陈旧 N 天")。
     */
    @Select("""
            SELECT ticker, market, trade_date, close_price, currency, source, fetched_at
              FROM stock_price_snapshot
             WHERE ticker = #{ticker}
               AND market = #{market}
             ORDER BY trade_date DESC
             LIMIT 1
            """)
    Optional<StockPriceSnapshot> findLatest(@Param("ticker") String ticker,
                                            @Param("market") String market);

    @Select("""
            SELECT ticker, market, trade_date, close_price, currency, source, fetched_at
              FROM stock_price_snapshot
             WHERE ticker = #{ticker}
               AND market = #{market}
               AND trade_date BETWEEN #{from} AND #{to}
             ORDER BY trade_date DESC
            """)
    List<StockPriceSnapshot> findRange(@Param("ticker") String ticker,
                                       @Param("market") String market,
                                       @Param("from") LocalDate from,
                                       @Param("to") LocalDate to);

    @Insert("""
            INSERT INTO stock_price_snapshot (ticker, market, trade_date, close_price, currency, source)
            VALUES (#{ticker}, #{market}, #{tradeDate}, #{closePrice}, #{currency}, #{source})
            ON DUPLICATE KEY UPDATE
                close_price = VALUES(close_price),
                currency = VALUES(currency),
                source = VALUES(source),
                fetched_at = CURRENT_TIMESTAMP
            """)
    int upsert(StockPriceSnapshot snapshot);
}
