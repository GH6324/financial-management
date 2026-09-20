package com.family.finance.repository;

import com.family.finance.domain.snapshot.SnapshotTodo;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * v1.24 · 家庭隔离:{@code snapshot_todo} 没有 {@code family_id} 列,
 * 归属沿 {@code snapshot_todo → period.family_id} 取,每条语句自己 JOIN。
 */
@Mapper
public interface SnapshotTodoMapper {

    String COLS = " td.id, td.period_id, td.account_id, td.assigned_member_id, td.status, td.done_at,"
            + " td.done_by_member_id, td.prefilled_balance, td.prefilled_transfer_id ";

    @Select("SELECT" + COLS + "FROM snapshot_todo td"
          + " JOIN period p ON p.id = td.period_id"
          + " WHERE p.family_id = #{familyId} AND td.id = #{id}")
    Optional<SnapshotTodo> findById(@Param("familyId") long familyId, @Param("id") long id);

    @Select("SELECT" + COLS + "FROM snapshot_todo td"
          + " JOIN period p ON p.id = td.period_id"
          + " WHERE p.family_id = #{familyId} AND td.period_id = #{periodId}"
          + " ORDER BY td.id")
    List<SnapshotTodo> findByPeriod(@Param("familyId") long familyId, @Param("periodId") long periodId);

    @Select("SELECT" + COLS + "FROM snapshot_todo td"
          + " JOIN period p ON p.id = td.period_id"
          + " WHERE p.family_id = #{familyId}"
          + " AND td.period_id = #{periodId} AND td.account_id = #{accountId}")
    Optional<SnapshotTodo> findByPeriodAndAccount(@Param("familyId") long familyId,
                                                  @Param("periodId") long periodId,
                                                  @Param("accountId") long accountId);

    @Select("SELECT" + COLS + "FROM snapshot_todo td"
          + " JOIN period p ON p.id = td.period_id"
          + " WHERE p.family_id = #{familyId}"
          + " AND td.period_id = #{periodId}"
          + " AND td.status = 'PENDING'"
          + " AND (td.assigned_member_id = #{memberId} OR td.assigned_member_id IS NULL)"
          + " ORDER BY td.id")
    List<SnapshotTodo> findPendingForMember(@Param("familyId") long familyId,
                                            @Param("periodId") long periodId,
                                            @Param("memberId") long memberId);

    @Select("SELECT COUNT(*) FROM snapshot_todo td"
          + " JOIN period p ON p.id = td.period_id"
          + " WHERE p.family_id = #{familyId}"
          + " AND td.period_id = #{periodId} AND td.status = 'PENDING'")
    int countPendingByPeriod(@Param("familyId") long familyId, @Param("periodId") long periodId);

    /**
     * 建一条待办。账期与账户必须同属这个家才真的插入。
     *
     * <p>{@code ON DUPLICATE KEY UPDATE} 保留 —— 开账是幂等的,重复开账只更新认领人。
     * 注意这让「影响行数」有三种取值:0=没插(归属不符或无变化)、1=新插、2=更新。
     * 所以 {@link #insertOwned} 判的是 {@code < 1} 而不是 {@code != 1}。</p>
     */
    @Insert("""
            INSERT INTO snapshot_todo (
                period_id, account_id, assigned_member_id, status,
                prefilled_balance, prefilled_transfer_id
            )
            SELECT #{td.periodId}, #{td.accountId}, #{td.assignedMemberId}, #{td.status},
                   #{td.prefilledBalance}, #{td.prefilledTransferId}
              FROM period p
              JOIN account a ON a.id = #{td.accountId}
             WHERE p.id = #{td.periodId}
               AND p.family_id = #{familyId}
               AND a.family_id = #{familyId}
            ON DUPLICATE KEY UPDATE
                assigned_member_id = VALUES(assigned_member_id)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "td.id")
    int insert(@Param("familyId") long familyId, @Param("td") SnapshotTodo todo);

    /** 带归属断言的插入 —— 业务代码一律用这个 */
    default void insertOwned(long familyId, SnapshotTodo todo) {
        if (insert(familyId, todo) < 1) {
            throw new IllegalStateException("待办归属校验不通过:账期 " + todo.getPeriodId()
                    + " / 账户 " + todo.getAccountId() + " 不属于家庭 " + familyId);
        }
    }

    @Update("""
            UPDATE snapshot_todo td
              JOIN period p ON p.id = td.period_id
               SET td.status = 'DONE',
                   td.done_at = NOW(3),
                   td.done_by_member_id = #{memberId}
             WHERE p.family_id = #{familyId}
               AND td.period_id = #{periodId}
               AND td.account_id = #{accountId}
            """)
    int markDone(@Param("familyId") long familyId,
                 @Param("periodId") long periodId,
                 @Param("accountId") long accountId,
                 @Param("memberId") long memberId);

    /**
     * v1.16 · 开账把上期末余额延续成本期快照时,同一行 todo 一并标 DONE(FR-390 · issue #15)。
     *
     * <p>不复用 {@link #markDone} —— 那个方法的语义是「<b>某个人</b>填完了」,签名里的 memberId 不该为 null;
     * 这里 {@code done_by_member_id} 故意留 NULL,表示<b>系统代填、还没有人确认过</b>,
     * 贷款趋势提示条靠这个区分继续出现(FR-392)。</p>
     *
     * <p>{@code AND status = 'PENDING'} 是保护:已经记名到人的行不会被反向抹成 NULL。</p>
     */
    @Update("""
            UPDATE snapshot_todo td
              JOIN period p ON p.id = td.period_id
               SET td.status = 'DONE',
                   td.done_at = NOW(3),
                   td.done_by_member_id = NULL
             WHERE p.family_id = #{familyId}
               AND td.period_id = #{periodId}
               AND td.account_id = #{accountId}
               AND td.status = 'PENDING'
            """)
    int markCarriedForward(@Param("familyId") long familyId,
                           @Param("periodId") long periodId,
                           @Param("accountId") long accountId);

    @Update("""
            UPDATE snapshot_todo td
              JOIN period p ON p.id = td.period_id
               SET td.prefilled_balance = #{td.prefilledBalance},
                   td.prefilled_transfer_id = #{td.prefilledTransferId}
             WHERE p.family_id = #{familyId}
               AND td.id = #{td.id}
            """)
    int updatePrefill(@Param("familyId") long familyId, @Param("td") SnapshotTodo todo);
}
