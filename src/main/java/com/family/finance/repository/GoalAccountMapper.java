package com.family.finance.repository;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * goal_account 表 Mapper · v0.16。目标↔账户 多对多绑定(0..N;空 = 全家)。
 *
 * <p>v1.24 · 家庭隔离:本表没有 {@code family_id} 列,归属沿
 * {@code goal_account → family_goal.family_id} 取;{@link #bind} 还要额外校验
 * <b>账户</b>也属于这个家 —— 否则一个目标可以绑上别人家的账户,
 * 之后目标进度会把那个账户的钱算进来。</p>
 */
@Mapper
public interface GoalAccountMapper {

    @Select("SELECT ga.account_id FROM goal_account ga"
          + " JOIN family_goal g ON g.id = ga.goal_id"
          + " WHERE g.family_id = #{familyId} AND ga.goal_id = #{goalId}"
          + " ORDER BY ga.account_id")
    List<Long> findAccountIds(@Param("familyId") long familyId, @Param("goalId") long goalId);

    /**
     * 绑一个账户到目标。目标与账户必须<b>同时</b>属于这个家。
     *
     * <p>{@code INSERT IGNORE} 保留(重复绑定幂等),因此影响行数 0 有两种含义
     * (已绑过 / 归属不符),不配断言;跨家庭写入由 SQL 本身拦住。</p>
     */
    @Insert("INSERT IGNORE INTO goal_account (goal_id, account_id)"
          + " SELECT #{goalId}, #{accountId}"
          + "   FROM family_goal g JOIN account a ON a.id = #{accountId}"
          + "  WHERE g.id = #{goalId} AND g.family_id = #{familyId} AND a.family_id = #{familyId}")
    int bind(@Param("familyId") long familyId,
             @Param("goalId") long goalId, @Param("accountId") long accountId);

    @Delete("DELETE ga FROM goal_account ga"
          + " JOIN family_goal g ON g.id = ga.goal_id"
          + " WHERE g.family_id = #{familyId} AND ga.goal_id = #{goalId}")
    int clear(@Param("familyId") long familyId, @Param("goalId") long goalId);
}
