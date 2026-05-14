package com.family.finance.repository;

import com.family.finance.domain.stock.StockValuationEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * v0.4.1 · stock_valuation_event mapper · ledger 查询 + 估值 hook 写入。
 */
@Mapper
public interface StockValuationEventMapper {

    @Insert("""
            INSERT INTO stock_valuation_event
                (family_id, account_id, period_id, prev_balance, new_balance, delta,
                 trigger_kind, triggered_by_member_id, note)
            VALUES
                (#{familyId}, #{accountId}, #{periodId}, #{prevBalance}, #{newBalance}, #{delta},
                 #{triggerKind}, #{triggeredByMemberId}, #{note})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(StockValuationEvent e);

    /** 按账户 + 周期查事件 · 给 ledger view 用 · 按 triggered_at 升序 */
    @Select("""
            SELECT id, family_id, account_id, period_id, prev_balance, new_balance, delta,
                   trigger_kind, triggered_by_member_id, note, triggered_at
              FROM stock_valuation_event
             WHERE account_id = #{accountId} AND period_id = #{periodId}
             ORDER BY triggered_at
            """)
    List<StockValuationEvent> findByAccountAndPeriod(@Param("accountId") long accountId,
                                                    @Param("periodId") long periodId);

    /** 按账户查所有期事件 · 给 accounts/{id} 详情页用 · 倒序 */
    @Select("""
            SELECT id, family_id, account_id, period_id, prev_balance, new_balance, delta,
                   trigger_kind, triggered_by_member_id, note, triggered_at
              FROM stock_valuation_event
             WHERE account_id = #{accountId}
             ORDER BY triggered_at DESC
             LIMIT #{limit}
            """)
    List<StockValuationEvent> findRecentByAccount(@Param("accountId") long accountId,
                                                  @Param("limit") int limit);
}
