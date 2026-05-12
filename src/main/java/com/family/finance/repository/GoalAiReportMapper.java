package com.family.finance.repository;

import com.family.finance.domain.goal.GoalAiReport;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * goal_ai_report 表 Mapper · v0.3 FR-53b/c。
 */
@Mapper
public interface GoalAiReportMapper {

    @Select("""
            SELECT id, goal_id, period_id, report_type, content, validator_status, generated_at, dismissed_at
              FROM goal_ai_report
             WHERE goal_id = #{goalId}
               AND report_type = #{reportType}
             ORDER BY generated_at DESC
             LIMIT 1
            """)
    Optional<GoalAiReport> findLatestByGoalAndType(@Param("goalId") long goalId,
                                                   @Param("reportType") String reportType);

    @Select("""
            SELECT id, goal_id, period_id, report_type, content, validator_status, generated_at, dismissed_at
              FROM goal_ai_report
             WHERE goal_id = #{goalId}
               AND period_id = #{periodId}
               AND report_type = #{reportType}
            """)
    Optional<GoalAiReport> findByGoalPeriodType(@Param("goalId") long goalId,
                                                @Param("periodId") long periodId,
                                                @Param("reportType") String reportType);

    /**
     * 90 天内是否有过 ALERT(FR-53c 节流)。
     */
    @Select("""
            SELECT COUNT(*)
              FROM goal_ai_report
             WHERE goal_id = #{goalId}
               AND report_type = 'ALERT'
               AND generated_at > DATE_SUB(NOW(), INTERVAL 90 DAY)
            """)
    int countRecentAlerts(@Param("goalId") long goalId);

    @Insert("""
            INSERT INTO goal_ai_report (goal_id, period_id, report_type, content, validator_status)
            VALUES (#{goalId}, #{periodId}, #{reportType}, #{content}, #{validatorStatus})
            ON DUPLICATE KEY UPDATE
                content = VALUES(content),
                validator_status = VALUES(validator_status),
                generated_at = CURRENT_TIMESTAMP,
                dismissed_at = NULL
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int upsert(GoalAiReport report);

    @Update("UPDATE goal_ai_report SET dismissed_at = NOW(3) WHERE id = #{id}")
    int dismiss(@Param("id") long id);

    @Select("""
            SELECT id, goal_id, period_id, report_type, content, validator_status, generated_at, dismissed_at
              FROM goal_ai_report
             WHERE goal_id = #{goalId}
             ORDER BY generated_at DESC
             LIMIT #{limit}
            """)
    List<GoalAiReport> findRecentByGoal(@Param("goalId") long goalId, @Param("limit") int limit);
}
