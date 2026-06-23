package com.family.finance.repository;

import com.family.finance.domain.transfer.Transfer;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

@Mapper
public interface TransferMapper {

    @Select("""
            SELECT id, period_id, from_account_id, to_account_id, amount, to_amount,
                   occurred_at, note, submitted_by, submitted_at, is_draft AS draft
              FROM transfer
             WHERE period_id = #{periodId}
               AND (from_account_id = #{accountId} OR to_account_id = #{accountId})
               AND deleted_at IS NULL
             ORDER BY submitted_at, id
            """)
    List<Transfer> findByPeriodAndAccount(@Param("periodId") long periodId,
                                          @Param("accountId") long accountId);

    @Select("""
            SELECT id, period_id, from_account_id, to_account_id, amount, to_amount,
                   occurred_at, note, submitted_by, submitted_at, is_draft AS draft
              FROM transfer
             WHERE id = #{id}
               AND deleted_at IS NULL
            """)
    Optional<Transfer> findById(@Param("id") long id);

    @Select("""
            SELECT t.id, t.period_id, t.from_account_id, t.to_account_id, t.amount, t.to_amount,
                   t.occurred_at, t.note, t.submitted_by, t.submitted_at, t.is_draft AS draft
              FROM transfer t
              JOIN period p ON p.id = t.period_id
             WHERE p.family_id = #{familyId}
               AND t.deleted_at IS NULL
             ORDER BY t.period_id, t.id
            """)
    List<Transfer> findAllByFamily(@Param("familyId") long familyId);

    @Select("""
            SELECT id, period_id, from_account_id, to_account_id, amount, to_amount,
                   occurred_at, note, submitted_by, submitted_at, is_draft AS draft
              FROM transfer
             WHERE period_id = #{periodId}
               AND (from_account_id = #{accountId} OR to_account_id = #{accountId})
               AND is_draft = 0
               AND deleted_at IS NULL
             ORDER BY submitted_at, id
            """)
    List<Transfer> findCommittedByPeriodAndAccount(@Param("periodId") long periodId,
                                                   @Param("accountId") long accountId);

    @Select("""
            SELECT COUNT(*)
              FROM transfer
             WHERE period_id = #{periodId}
               AND from_account_id = #{fromAccountId}
               AND to_account_id = #{toAccountId}
               AND amount = #{amount}
               AND is_draft = 0
               AND deleted_at IS NULL
               AND submitted_at >= NOW(3) - INTERVAL 24 HOUR
            """)
    int countRecentDuplicate(@Param("periodId") long periodId,
                             @Param("fromAccountId") long fromAccountId,
                             @Param("toAccountId") long toAccountId,
                             @Param("amount") java.math.BigDecimal amount);

    @Insert("""
            INSERT INTO transfer (
                period_id, from_account_id, to_account_id, amount, to_amount,
                occurred_at, note, submitted_by, is_draft
            ) VALUES (
                #{periodId}, #{fromAccountId}, #{toAccountId}, #{amount}, #{toAmount},
                #{occurredAt}, #{note}, #{submittedBy}, #{draft}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Transfer transfer);

    @Update("""
            UPDATE transfer
               SET amount = #{amount},
                   to_amount = #{toAmount},
                   occurred_at = #{occurredAt},
                   note = #{note},
                   submitted_by = #{submittedBy},
                   submitted_at = NOW(3),
                   is_draft = #{draft}
             WHERE id = #{id}
            """)
    int updateAmountAndDraft(Transfer transfer);

    /** v0.2 FR-32 · 软删:UPDATE deleted_at = NOW(3) */
    @Update("""
            UPDATE transfer
               SET deleted_at = NOW(3)
             WHERE id = #{id}
               AND deleted_at IS NULL
            """)
    int softDelete(@Param("id") long id);
}
