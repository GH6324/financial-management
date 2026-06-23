package com.family.finance.repository;

import com.family.finance.domain.flow.CashFlow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.Optional;

import java.util.List;

@Mapper
public interface CashFlowMapper {

    @Select("""
            SELECT id, period_id, account_id, kind, category_code, amount,
                   occurred_at, note, submitted_by, submitted_at
              FROM cash_flow
             WHERE period_id = #{periodId}
               AND account_id = #{accountId}
               AND deleted_at IS NULL
             ORDER BY submitted_at, id
            """)
    List<CashFlow> findByPeriodAndAccount(@Param("periodId") long periodId,
                                          @Param("accountId") long accountId);

    @Select("""
            SELECT cf.id, cf.period_id, cf.account_id, cf.kind, cf.category_code, cf.amount,
                   cf.occurred_at, cf.note, cf.submitted_by, cf.submitted_at
              FROM cash_flow cf
              JOIN period p ON p.id = cf.period_id
             WHERE p.family_id = #{familyId}
               AND cf.deleted_at IS NULL
             ORDER BY cf.period_id, cf.id
            """)
    List<CashFlow> findAllByFamily(@Param("familyId") long familyId);

    @Insert("""
            INSERT INTO cash_flow (
                period_id, account_id, kind, category_code, amount, occurred_at, note, submitted_by, is_adjustment
            ) VALUES (
                #{periodId}, #{accountId}, #{kind}, #{categoryCode}, #{amount}, #{occurredAt}, #{note}, #{submittedBy}, #{adjustment}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(CashFlow cashFlow);

    /** v0.2 FR-32 · 按 id 取一行(含已删的);用于软删校验家庭归属与周期 */
    @Select("""
            SELECT id, period_id, account_id, kind, category_code, amount,
                   occurred_at, note, submitted_by, submitted_at
              FROM cash_flow
             WHERE id = #{id}
            """)
    Optional<CashFlow> findById(@Param("id") long id);

    /** v0.2 FR-32 · 软删:UPDATE deleted_at = NOW(3) */
    @Update("""
            UPDATE cash_flow
               SET deleted_at = NOW(3)
             WHERE id = #{id}
               AND deleted_at IS NULL
            """)
    int softDelete(@Param("id") long id);
}
