package com.family.finance.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

/** v1.2 · 再平衡计划(V48 · tech-design v1.2 §3) */
@Mapper
public interface RebalancePlanMapper {

    record Plan(Long id, Long familyId, Long periodId, String status) {}

    record Item(Long id, Long planId, Long fromAccountId, Long toAccountId,
                BigDecimal amountBase, String note, String status,
                Long executedTransferId, String fromName, String toName) {}

    @Select("SELECT id, family_id AS familyId, period_id AS periodId, status FROM rebalance_plan WHERE family_id=#{familyId} AND status='ACTIVE' LIMIT 1")
    Plan findActive(@Param("familyId") long familyId);

    @Insert("INSERT INTO rebalance_plan (family_id, period_id) VALUES (#{plan.familyId}, #{plan.periodId})")
    @Options(useGeneratedKeys = true, keyProperty = "plan.id")
    int insertPlan(@Param("plan") PlanRow plan);

    class PlanRow { public Long id; public long familyId; public long periodId; }

    @Update("UPDATE rebalance_plan SET status='ARCHIVED', closed_at=NOW() WHERE family_id=#{familyId} AND status='ACTIVE'")
    int archiveActive(@Param("familyId") long familyId);

    @Select("""
            SELECT i.id, i.plan_id AS planId, i.from_account_id AS fromAccountId, i.to_account_id AS toAccountId,
                   i.amount_base AS amountBase, i.note, i.status, i.executed_transfer_id AS executedTransferId,
                   fa.display_name AS fromName, ta.display_name AS toName
              FROM rebalance_plan_item i
              JOIN account fa ON fa.id = i.from_account_id
              JOIN account ta ON ta.id = i.to_account_id
              JOIN rebalance_plan pl ON pl.id = i.plan_id
             WHERE pl.family_id = #{familyId}
               AND i.plan_id = #{planId}
             ORDER BY i.id
            """)
    List<Item> findItems(@Param("familyId") long familyId, @Param("planId") long planId);

    @Insert("INSERT INTO rebalance_plan_item (plan_id, from_account_id, to_account_id, amount_base, note)"
          + " SELECT #{planId}, #{fromId}, #{toId}, #{amount}, #{note}"
          + "   FROM rebalance_plan pl"
          + "   JOIN account fa ON fa.id = #{fromId}"
          + "   JOIN account ta ON ta.id = #{toId}"
          + "  WHERE pl.id = #{planId} AND pl.family_id = #{familyId}"
          + "    AND fa.family_id = #{familyId} AND ta.family_id = #{familyId}")
    int insertItem(@Param("familyId") long familyId,
                   @Param("planId") long planId, @Param("fromId") long fromId,
                   @Param("toId") long toId, @Param("amount") BigDecimal amount, @Param("note") String note);

    /** 带归属断言的插入 —— 业务代码一律用这个 */
    default void insertItemOwned(long familyId, long planId, long fromId, long toId,
                                 BigDecimal amount, String note) {
        if (insertItem(familyId, planId, fromId, toId, amount, note) != 1) {
            throw new IllegalStateException("再平衡条目归属校验不通过:计划 " + planId
                    + " / 转出 " + fromId + " / 转入 " + toId + " 不全属于家庭 " + familyId);
        }
    }

    @Update("UPDATE rebalance_plan_item i JOIN rebalance_plan pl ON pl.id = i.plan_id"
          + " SET i.status='EXECUTED', i.executed_transfer_id=#{transferId}, i.executed_at=NOW()"
          + " WHERE pl.family_id=#{familyId} AND i.id=#{itemId} AND i.status='PENDING'")
    int markExecuted(@Param("familyId") long familyId,
                     @Param("itemId") long itemId, @Param("transferId") long transferId);

    @Update("UPDATE rebalance_plan_item i JOIN rebalance_plan pl ON pl.id = i.plan_id"
          + " SET i.status='MANUAL_DONE', i.executed_at=NOW()"
          + " WHERE pl.family_id=#{familyId} AND i.id=#{itemId} AND i.status='PENDING'")
    int markManualDone(@Param("familyId") long familyId, @Param("itemId") long itemId);

    @Update("UPDATE rebalance_plan_item i JOIN rebalance_plan pl ON pl.id = i.plan_id"
          + " SET i.amount_base=#{amount}"
          + " WHERE pl.family_id=#{familyId} AND i.id=#{itemId} AND i.status='PENDING'")
    int updateAmount(@Param("familyId") long familyId,
                     @Param("itemId") long itemId, @Param("amount") BigDecimal amount);

    @Update("DELETE i FROM rebalance_plan_item i JOIN rebalance_plan pl ON pl.id = i.plan_id"
          + " WHERE pl.family_id=#{familyId} AND i.id=#{itemId}")
    int deleteItem(@Param("familyId") long familyId, @Param("itemId") long itemId);

    @Select("SELECT COUNT(*) FROM rebalance_plan_item i JOIN rebalance_plan pl ON pl.id = i.plan_id"
          + " WHERE pl.family_id=#{familyId} AND i.plan_id=#{planId}")
    int countItems(@Param("familyId") long familyId, @Param("planId") long planId);
}
