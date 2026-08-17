package com.family.finance.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * v1.15 FR-383 · 成员引用扫描 —— 删除前逐表数「这个人身上还挂着多少条记录」。
 *
 * <p><b>为什么是手写清单而不是 information_schema 自动发现</b>(TDD v1.15 §2.4):
 * 自动发现只能找到**外键**,而这个库里有 4 处引用根本没有外键 ——
 * {@code period_member_cashflow.member_id}(V19)、{@code stock_valuation_event.triggered_by_member_id}(V24)、
 * {@code report_reminder_log.member_id}(V25)、以及 {@code family_goal.params_json} 里的
 * {@code $.child_member_id}(JSON 字段,任何 schema 元数据都看不见)。
 * 自动发现会给出一个**自信的错误答案**:扫出 0 引用 → 允许删 → 教育目标的孩子指针悬空。
 *
 * <p>所以这里是**显式的 13 条**,加表就得加行 —— 护栏
 * {@code v115-DELETE-SCAN-COVERS-FK-LESS} 逐个点名那 4 处没外键的,防止后来者以为「外键都覆盖了」。
 */
@Mapper
public interface MemberReferenceMapper {

    // --- 有外键的 9 处 ------------------------------------------------------

    @Select("SELECT COUNT(*) FROM account WHERE primary_owner_member_id = #{memberId}")
    int countAccountOwner(@Param("memberId") long memberId);

    @Select("SELECT COUNT(*) FROM period_snapshot WHERE submitted_by = #{memberId}")
    int countPeriodSnapshot(@Param("memberId") long memberId);

    @Select("SELECT COUNT(*) FROM cash_flow WHERE submitted_by = #{memberId}")
    int countCashFlow(@Param("memberId") long memberId);

    @Select("SELECT COUNT(*) FROM transfer WHERE submitted_by = #{memberId}")
    int countTransfer(@Param("memberId") long memberId);

    @Select("SELECT COUNT(*) FROM snapshot_todo WHERE assigned_member_id = #{memberId}")
    int countTodoAssigned(@Param("memberId") long memberId);

    @Select("SELECT COUNT(*) FROM snapshot_todo WHERE done_by_member_id = #{memberId}")
    int countTodoDoneBy(@Param("memberId") long memberId);

    @Select("SELECT COUNT(*) FROM period_member_completion WHERE member_id = #{memberId}")
    int countPeriodCompletion(@Param("memberId") long memberId);

    @Select("SELECT COUNT(*) FROM audit_log WHERE actor_member_id = #{memberId}")
    int countAuditActor(@Param("memberId") long memberId);

    @Select("SELECT COUNT(*) FROM period_reopen_log WHERE reopened_by = #{memberId}")
    int countPeriodReopen(@Param("memberId") long memberId);

    // --- 没有外键的 4 处 · 自动发现看不见,只能手写 ---------------------------

    /** V19 · 期间成员现金流(没建外键) */
    @Select("SELECT COUNT(*) FROM period_member_cashflow WHERE member_id = #{memberId}")
    int countPeriodMemberCashflow(@Param("memberId") long memberId);

    /** V24 · 股票估值事件(没建外键) */
    @Select("SELECT COUNT(*) FROM stock_valuation_event WHERE triggered_by_member_id = #{memberId}")
    int countStockValuationEvent(@Param("memberId") long memberId);

    /** V25 · 报告提醒发送记录(没建外键) */
    @Select("SELECT COUNT(*) FROM report_reminder_log WHERE member_id = #{memberId}")
    int countReportReminderLog(@Param("memberId") long memberId);

    /**
     * V14 · 教育目标里指向孩子的 JSON 指针 —— 没有外键、没有独立列,
     * 只有 {@code params_json} 里的 {@code $.child_member_id}。
     * 存进去的是数字还是字符串取决于写入路径,所以两边都转成 CHAR 再比。
     */
    @Select("""
            SELECT COUNT(*)
              FROM family_goal
             WHERE family_id = #{familyId}
               AND JSON_UNQUOTE(JSON_EXTRACT(params_json, '$.child_member_id')) = CAST(#{memberId} AS CHAR)
            """)
    int countGoalChildRef(@Param("familyId") long familyId, @Param("memberId") long memberId);
}
