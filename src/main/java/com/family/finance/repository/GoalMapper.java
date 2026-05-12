package com.family.finance.repository;

import com.family.finance.domain.goal.Goal;
import com.family.finance.domain.goal.GoalType;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * family_goal 表 Mapper · v0.3 FR-50。
 *
 * <p>软删通过 archived_at IS NOT NULL 标记 · 沿用 v0.2 风格 · 不真删行。</p>
 */
@Mapper
public interface GoalMapper {

    @Select("""
            SELECT id, family_id, goal_type, name, target_value, target_date,
                   params_json, created_at, updated_at, archived_at
              FROM family_goal
             WHERE id = #{id}
            """)
    Optional<Goal> findById(@Param("id") long id);

    @Select("""
            SELECT id, family_id, goal_type, name, target_value, target_date,
                   params_json, created_at, updated_at, archived_at
              FROM family_goal
             WHERE family_id = #{familyId}
               AND archived_at IS NULL
             ORDER BY id
            """)
    List<Goal> findActiveByFamily(@Param("familyId") long familyId);

    @Select("""
            SELECT id, family_id, goal_type, name, target_value, target_date,
                   params_json, created_at, updated_at, archived_at
              FROM family_goal
             WHERE family_id = #{familyId}
               AND goal_type = #{goalType}
               AND archived_at IS NULL
             ORDER BY id
            """)
    List<Goal> findActiveByFamilyAndType(@Param("familyId") long familyId,
                                         @Param("goalType") GoalType goalType);

    @Insert("""
            INSERT INTO family_goal (family_id, goal_type, name, target_value, target_date, params_json)
            VALUES (#{familyId}, #{goalType}, #{name}, #{targetValue}, #{targetDate}, #{paramsJson})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Goal goal);

    @Update("""
            UPDATE family_goal
               SET name = #{name},
                   target_value = #{targetValue},
                   target_date = #{targetDate},
                   params_json = #{paramsJson}
             WHERE id = #{id}
               AND family_id = #{familyId}
               AND archived_at IS NULL
            """)
    int update(Goal goal);

    @Update("""
            UPDATE family_goal
               SET archived_at = NOW(3)
             WHERE id = #{id}
               AND family_id = #{familyId}
               AND archived_at IS NULL
            """)
    int archive(@Param("familyId") long familyId, @Param("id") long id);

    @Update("""
            UPDATE family_goal
               SET archived_at = NULL
             WHERE id = #{id}
               AND family_id = #{familyId}
               AND archived_at IS NOT NULL
            """)
    int restore(@Param("familyId") long familyId, @Param("id") long id);
}
