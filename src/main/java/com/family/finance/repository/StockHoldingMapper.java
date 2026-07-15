package com.family.finance.repository;

import com.family.finance.domain.stock.StockHolding;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * stock_holding 表 Mapper · v0.3 FR-52。
 */
@Mapper
public interface StockHoldingMapper {

    @Select("""
            SELECT id, account_id, display_name, valuation_mode, ticker, market, shares,
                   cost_basis, currency, unit, sync_source AS syncSource, industry_tag AS industryTag, manual_value, manual_value_at, cash_linked AS cashLinked,
                   archived_at, created_at, updated_at
              FROM stock_holding
             WHERE id = #{id}
            """)
    Optional<StockHolding> findById(@Param("id") long id);

    @Select("""
            SELECT id, account_id, display_name, valuation_mode, ticker, market, shares,
                   cost_basis, currency, unit, sync_source AS syncSource, industry_tag AS industryTag, manual_value, manual_value_at, cash_linked AS cashLinked,
                   archived_at, created_at, updated_at
              FROM stock_holding
             WHERE account_id = #{accountId}
               AND archived_at IS NULL
             ORDER BY id
            """)
    List<StockHolding> findActiveByAccount(@Param("accountId") long accountId);

    @Select("""
            SELECT id, account_id, display_name, valuation_mode, ticker, market, shares,
                   cost_basis, currency, unit, sync_source AS syncSource, industry_tag AS industryTag, manual_value, manual_value_at, cash_linked AS cashLinked,
                   archived_at, created_at, updated_at
              FROM stock_holding
             WHERE account_id = #{accountId}
             ORDER BY archived_at IS NULL DESC, id
            """)
    List<StockHolding> findAllByAccount(@Param("accountId") long accountId);

    /**
     * 跨所有家庭找全部 AUTO 持仓的不重复 (market, ticker) · 拉价 cron 用。
     */
    @Select("""
            SELECT DISTINCT ticker, market
              FROM stock_holding
             WHERE valuation_mode = 'AUTO'
               AND market = #{market}
               AND archived_at IS NULL
               AND ticker IS NOT NULL
            """)
    List<TickerMarket> findDistinctAutoTickersByMarket(@Param("market") String market);

    @Insert("""
            INSERT INTO stock_holding (account_id, display_name, valuation_mode, ticker, market, shares,
                                       cost_basis, currency, unit, sync_source, industry_tag, manual_value, manual_value_at, cash_linked)
            VALUES (#{accountId}, #{displayName}, #{valuationMode}, #{ticker}, #{market}, #{shares},
                    #{costBasis}, #{currency}, #{unit}, #{syncSource}, #{industryTag}, #{manualValue}, #{manualValueAt}, #{cashLinked})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(StockHolding holding);

    @Update("""
            UPDATE stock_holding
               SET display_name = #{displayName},
                   valuation_mode = #{valuationMode},
                   ticker = #{ticker},
                   market = #{market},
                   shares = #{shares},
                   cost_basis = #{costBasis},
                   currency = #{currency},
                   unit = #{unit},
                   sync_source = #{syncSource},
                   industry_tag = #{industryTag},
                   manual_value = #{manualValue},
                   manual_value_at = #{manualValueAt},
                   cash_linked = #{cashLinked}
             WHERE id = #{id}
               AND archived_at IS NULL
            """)
    int update(StockHolding holding);

    @Update("UPDATE stock_holding SET archived_at = NOW(3) WHERE id = #{id} AND archived_at IS NULL")
    int archive(@Param("id") long id);

    /** v0.15 · 解绑券商:把该账户所有 sync_source 持仓清为普通持仓(保留可手动维护) */
    @Update("UPDATE stock_holding SET sync_source = NULL WHERE account_id = #{accountId} AND sync_source IS NOT NULL")
    int clearSyncSource(@Param("accountId") long accountId);

    @Update("UPDATE stock_holding SET archived_at = NULL WHERE id = #{id} AND archived_at IS NOT NULL")
    int restore(@Param("id") long id);

    /** v1.1 · 单改行业标(持仓页行内下拉 · 资产透视维度) */
    @Update("UPDATE stock_holding SET industry_tag = #{industryTag} WHERE id = #{id}")
    int updateIndustry(@Param("id") long id, @Param("industryTag") String industryTag);

    /**
     * 轻量值对象 · 仅给 fetcher cron 用。
     */
    record TickerMarket(String ticker, String market) {}
}
