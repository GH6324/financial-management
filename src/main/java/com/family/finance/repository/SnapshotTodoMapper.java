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

@Mapper
public interface SnapshotTodoMapper {

    @Select("""
            SELECT id, period_id, account_id, assigned_member_id, status, done_at,
                   done_by_member_id, prefilled_balance, prefilled_transfer_id
              FROM snapshot_todo
             WHERE id = #{id}
            """)
    Optional<SnapshotTodo> findById(@Param("id") long id);

    @Select("""
            SELECT id, period_id, account_id, assigned_member_id, status, done_at,
                   done_by_member_id, prefilled_balance, prefilled_transfer_id
              FROM snapshot_todo
             WHERE period_id = #{periodId}
             ORDER BY id
            """)
    List<SnapshotTodo> findByPeriod(@Param("periodId") long periodId);

    @Select("""
            SELECT id, period_id, account_id, assigned_member_id, status, done_at,
                   done_by_member_id, prefilled_balance, prefilled_transfer_id
              FROM snapshot_todo
             WHERE period_id = #{periodId}
               AND account_id = #{accountId}
            """)
    Optional<SnapshotTodo> findByPeriodAndAccount(@Param("periodId") long periodId,
                                                  @Param("accountId") long accountId);

    @Select("""
            SELECT id, period_id, account_id, assigned_member_id, status, done_at,
                   done_by_member_id, prefilled_balance, prefilled_transfer_id
              FROM snapshot_todo
             WHERE period_id = #{periodId}
               AND status = 'PENDING'
               AND (assigned_member_id = #{memberId} OR assigned_member_id IS NULL)
             ORDER BY id
            """)
    List<SnapshotTodo> findPendingForMember(@Param("periodId") long periodId,
                                            @Param("memberId") long memberId);

    @Select("""
            SELECT COUNT(*)
              FROM snapshot_todo
             WHERE period_id = #{periodId}
               AND status = 'PENDING'
            """)
    int countPendingByPeriod(@Param("periodId") long periodId);

    @Insert("""
            INSERT INTO snapshot_todo (
                period_id, account_id, assigned_member_id, status,
                prefilled_balance, prefilled_transfer_id
            ) VALUES (
                #{periodId}, #{accountId}, #{assignedMemberId}, #{status},
                #{prefilledBalance}, #{prefilledTransferId}
            )
            ON DUPLICATE KEY UPDATE
                assigned_member_id = VALUES(assigned_member_id)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SnapshotTodo todo);

    @Update("""
            UPDATE snapshot_todo
               SET status = 'DONE',
                   done_at = NOW(3),
                   done_by_member_id = #{memberId}
             WHERE period_id = #{periodId}
               AND account_id = #{accountId}
            """)
    int markDone(@Param("periodId") long periodId,
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
            UPDATE snapshot_todo
               SET status = 'DONE',
                   done_at = NOW(3),
                   done_by_member_id = NULL
             WHERE period_id = #{periodId}
               AND account_id = #{accountId}
               AND status = 'PENDING'
            """)
    int markCarriedForward(@Param("periodId") long periodId,
                           @Param("accountId") long accountId);

    @Update("""
            UPDATE snapshot_todo
               SET prefilled_balance = #{prefilledBalance},
                   prefilled_transfer_id = #{prefilledTransferId}
             WHERE id = #{id}
            """)
    int updatePrefill(SnapshotTodo todo);
}
