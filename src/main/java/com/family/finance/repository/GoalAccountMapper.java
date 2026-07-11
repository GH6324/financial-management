package com.family.finance.repository;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * goal_account 表 Mapper · v0.16。目标↔账户 多对多绑定(0..N;空 = 全家)。
 */
@Mapper
public interface GoalAccountMapper {

    @Select("SELECT account_id FROM goal_account WHERE goal_id = #{goalId} ORDER BY account_id")
    List<Long> findAccountIds(@Param("goalId") long goalId);

    @Insert("INSERT IGNORE INTO goal_account (goal_id, account_id) VALUES (#{goalId}, #{accountId})")
    int bind(@Param("goalId") long goalId, @Param("accountId") long accountId);

    @Delete("DELETE FROM goal_account WHERE goal_id = #{goalId}")
    int clear(@Param("goalId") long goalId);
}
