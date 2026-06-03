package com.family.finance.repository;

import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodType;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Period 表 Mapper · v0.2 行为完全保留。
 *
 * <p>v0.3 早期版本曾在此 Mapper 加了 total_income_input / total_expense_input(V15 家庭级)
 * 相关字段和方法。2026-05-13 修订后改为成员级表 {@code period_member_cashflow}(V19),
 * 此 Mapper 回到 v0.2 风格:不读 V15 加的两列,见 {@link PeriodMemberCashflowMapper}。</p>
 */
@Mapper
public interface PeriodMapper {

    @Select("""
            SELECT id, family_id, period_type, period_start, period_end, status, closed_at, created_at
              FROM period
             WHERE id = #{id}
            """)
    Optional<Period> findById(@Param("id") long id);

    @Select("""
            SELECT id, family_id, period_type, period_start, period_end, status, closed_at, created_at
              FROM period
             WHERE family_id = #{familyId}
               AND status = 'OPEN'
             ORDER BY period_start DESC
             LIMIT 1
            """)
    Optional<Period> findCurrentOpen(@Param("familyId") long familyId);

    @Select("""
            SELECT id, family_id, period_type, period_start, period_end, status, closed_at, created_at
              FROM period
             WHERE family_id = #{familyId}
               AND period_type = #{periodType}
               AND period_start = #{periodStart}
            """)
    Optional<Period> findByNatural(@Param("familyId") long familyId,
                                   @Param("periodType") PeriodType periodType,
                                   @Param("periodStart") LocalDate periodStart);

    @Select("""
            SELECT id, family_id, period_type, period_start, period_end, status, closed_at, created_at
              FROM period
             WHERE family_id = #{familyId}
               AND period_start BETWEEN #{from} AND #{to}
             ORDER BY period_start DESC
            """)
    List<Period> findRange(@Param("familyId") long familyId,
                           @Param("from") LocalDate from,
                           @Param("to") LocalDate to);

    @Select("""
            SELECT id, family_id, period_type, period_start, period_end, status, closed_at, created_at
              FROM period
             WHERE family_id = #{familyId}
             ORDER BY period_start DESC
             LIMIT #{limit}
            """)
    List<Period> findLatest(@Param("familyId") long familyId, @Param("limit") int limit);

    /**
     * v0.5.5 · 报表快照锚定 · 最近一个「已关账(CLOSED)且 period_start ≤ asOf」的账期。
     * <p>asOf 通常传服务器今天 —— 顺带挡掉测试/误建的未来 CLOSED 账期(如 2032)。</p>
     */
    @Select("""
            SELECT id, family_id, period_type, period_start, period_end, status, closed_at, created_at
              FROM period
             WHERE family_id = #{familyId}
               AND status = 'CLOSED'
               AND period_start <= #{asOf}
             ORDER BY period_start DESC
             LIMIT 1
            """)
    Optional<Period> findLatestClosedAsOf(@Param("familyId") long familyId,
                                          @Param("asOf") LocalDate asOf);

    /** v0.5 修 · 周期管理分页(倒序 · 新→旧)· offset/limit。 */
    @Select("""
            SELECT id, family_id, period_type, period_start, period_end, status, closed_at, created_at
              FROM period
             WHERE family_id = #{familyId}
             ORDER BY period_start DESC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<Period> findPaged(@Param("familyId") long familyId,
                           @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM period WHERE family_id = #{familyId}")
    int countByFamily(@Param("familyId") long familyId);

    @Select("""
            SELECT id, family_id, period_type, period_start, period_end, status, closed_at, created_at
              FROM period
             WHERE family_id = #{familyId}
             ORDER BY period_start
            """)
    List<Period> findAllByFamily(@Param("familyId") long familyId);

    @Insert("""
            INSERT INTO period (family_id, period_type, period_start, period_end, status)
            VALUES (#{familyId}, #{periodType}, #{periodStart}, #{periodEnd}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Period period);

    @Update("""
            UPDATE period
               SET status = 'CLOSED',
                   closed_at = NOW(3)
             WHERE id = #{id}
               AND family_id = #{familyId}
            """)
    int close(@Param("familyId") long familyId, @Param("id") long id);

    @Update("""
            UPDATE period
               SET status = 'OPEN',
                   closed_at = NULL
             WHERE id = #{id}
               AND family_id = #{familyId}
            """)
    int reopen(@Param("familyId") long familyId, @Param("id") long id);
}
