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

    @Insert("INSERT INTO rebalance_plan (family_id, period_id) VALUES (#{familyId}, #{periodId})")
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
             WHERE i.plan_id = #{planId}
             ORDER BY i.id
            """)
    List<Item> findItems(@Param("planId") long planId);

    @Insert("INSERT INTO rebalance_plan_item (plan_id, from_account_id, to_account_id, amount_base, note) VALUES (#{planId}, #{fromId}, #{toId}, #{amount}, #{note})")
    int insertItem(@Param("planId") long planId, @Param("fromId") long fromId,
                   @Param("toId") long toId, @Param("amount") BigDecimal amount, @Param("note") String note);

    @Update("UPDATE rebalance_plan_item SET status='EXECUTED', executed_transfer_id=#{transferId}, executed_at=NOW() WHERE id=#{itemId} AND status='PENDING'")
    int markExecuted(@Param("itemId") long itemId, @Param("transferId") long transferId);

    @Update("UPDATE rebalance_plan_item SET status='MANUAL_DONE', executed_at=NOW() WHERE id=#{itemId} AND status='PENDING'")
    int markManualDone(@Param("itemId") long itemId);

    @Update("UPDATE rebalance_plan_item SET amount_base=#{amount} WHERE id=#{itemId} AND status='PENDING'")
    int updateAmount(@Param("itemId") long itemId, @Param("amount") BigDecimal amount);

    @Update("DELETE FROM rebalance_plan_item WHERE id=#{itemId}")
    int deleteItem(@Param("itemId") long itemId);

    @Select("SELECT COUNT(*) FROM rebalance_plan_item WHERE plan_id=#{planId}")
    int countItems(@Param("planId") long planId);
}
