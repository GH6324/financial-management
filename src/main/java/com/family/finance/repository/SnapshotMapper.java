package com.family.finance.repository;

import com.family.finance.domain.snapshot.PeriodSnapshot;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Mapper
public interface SnapshotMapper {

    /**
     * v0.3 FR-50c · 应急储备 PV · 按账户 type 过滤求 end_balance 之和。
     * 假设 CASH 类账户币种 = 家庭本位币(若混合多币种,后续 v0.4 需走 fx 换算)。
     */
    @Select("""
            SELECT COALESCE(SUM(ps.end_balance), 0)
              FROM period_snapshot ps
              JOIN account a ON a.id = ps.account_id
             WHERE ps.period_id = #{periodId}
               AND a.family_id = #{familyId}
               AND a.type = #{accountType}
               AND a.archived_at IS NULL
            """)
    Optional<BigDecimal> sumEndBalanceByAccountType(@Param("familyId") long familyId,
                                                    @Param("periodId") long periodId,
                                                    @Param("accountType") String accountType);


    @Select("""
            SELECT id, period_id, account_id, end_balance, submitted_by, submitted_at, note
              FROM period_snapshot
             WHERE period_id = #{periodId}
               AND account_id = #{accountId}
            """)
    Optional<PeriodSnapshot> findByPeriodAndAccount(@Param("periodId") long periodId,
                                                    @Param("accountId") long accountId);

    @Select("""
            SELECT id, period_id, account_id, end_balance, submitted_by, submitted_at, note
              FROM period_snapshot
             WHERE period_id = #{periodId}
            """)
    List<PeriodSnapshot> findByPeriod(@Param("periodId") long periodId);

    @Select("""
            SELECT ps.id, ps.period_id, ps.account_id, ps.end_balance, ps.submitted_by, ps.submitted_at, ps.note
              FROM period_snapshot ps
              JOIN period p ON p.id = ps.period_id
             WHERE p.family_id = #{familyId}
             ORDER BY ps.period_id, ps.account_id
            """)
    List<PeriodSnapshot> findAllByFamily(@Param("familyId") long familyId);

    @Select("""
            SELECT ps.id, ps.period_id, ps.account_id, ps.end_balance, ps.submitted_by, ps.submitted_at, ps.note
              FROM period_snapshot ps
              JOIN period p ON p.id = ps.period_id
             WHERE ps.account_id = #{accountId}
               AND p.period_start < #{before}
             ORDER BY p.period_start DESC
             LIMIT #{limit}
            """)
    List<PeriodSnapshot> findLatestBefore(@Param("accountId") long accountId,
                                          @Param("before") LocalDate before,
                                          @Param("limit") int limit);

    @Insert("""
            INSERT INTO period_snapshot (period_id, account_id, end_balance, submitted_by, note)
            VALUES (#{periodId}, #{accountId}, #{endBalance}, #{submittedBy}, #{note})
            ON DUPLICATE KEY UPDATE
                end_balance = VALUES(end_balance),
                submitted_by = VALUES(submitted_by),
                submitted_at = NOW(3),
                note = VALUES(note)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int upsert(PeriodSnapshot snapshot);
}
