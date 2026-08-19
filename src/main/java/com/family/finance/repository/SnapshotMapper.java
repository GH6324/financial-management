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

    /**
     * v0.13 · 「开账基线」检测:在 periodId **首次出现**的账户 id
     * = 该期有快照、且**此前任何一期都没有快照**的账户。
     * 用于把"中途新增账户的存量本金"从当期投资收益里剔除(它是外部资本纳入,非当期赚)。
     */
    @Select("""
            SELECT DISTINCT ps.account_id
              FROM period_snapshot ps
             WHERE ps.period_id = #{periodId}
               AND ps.account_id NOT IN (
                   SELECT ps2.account_id
                     FROM period_snapshot ps2
                     JOIN period p2 ON p2.id = ps2.period_id
                    WHERE p2.family_id = #{familyId}
                      AND p2.period_start < (SELECT period_start FROM period WHERE id = #{periodId}))
            """)
    List<Long> firstAppearingAccountIds(@Param("familyId") long familyId,
                                        @Param("periodId") long periodId);

    /**
     * v1.11 · 一次查出**全家庭**「每个账户首次出现在哪一期」。
     *
     * <p>上面那条是按期查的,而调用它的地方全是 per-period 循环
     * ({@code openingBaseline} / {@code periodFlows} / {@code netWorthTrendExOpening} /
     * {@code accountPerformance}),一个 12 期窗口就打 12+ 次,报表页实测一次请求 881 条 SQL。
     * 而「首次出现」是**账户的属性**、与查哪一期无关 —— 一次查完在内存里分组即可。</p>
     *
     * <p>返回 {@code account_id → 首次出现的 period_id}。口径与上面那条**完全等价**:
     * 都以 {@code period_start} 升序取该账户最早有快照的那一期。</p>
     */
    // v1.11 · **一次扫完**:窗口函数按账户分组取 period_start 最早的那行。
    //   第一版写成了相关子查询(对 period_snapshot 每行再查一次 MIN)—— 3600 行 × 全表扫,
    //   O(n²),实测把报表页从 1.25s 拖到 9.3s。教训:「一条 SQL」不等于「一次扫描」,
    //   合并查询的时候必须看执行计划,不能只数条数。
    @Select("""
            SELECT t.account_id AS accountId, t.period_id AS periodId
              FROM (SELECT ps.account_id, ps.period_id,
                           ROW_NUMBER() OVER (PARTITION BY ps.account_id ORDER BY p.period_start) AS rn
                      FROM period_snapshot ps
                      JOIN period p ON p.id = ps.period_id
                     WHERE p.family_id = #{familyId}) t
             WHERE t.rn = 1
            """)
    List<FirstAppearance> firstAppearanceByAccount(@Param("familyId") long familyId);

    /** account → 首次出现的 period(v1.11 批量口径) */
    record FirstAppearance(Long accountId, Long periodId) {
    }


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
            SELECT ps.id, ps.period_id, ps.account_id, ps.end_balance, ps.submitted_by, ps.submitted_at, ps.note,
                   ps.source_tag AS sourceTag
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
            INSERT INTO period_snapshot (period_id, account_id, end_balance, submitted_by, note, source_tag)
            VALUES (#{periodId}, #{accountId}, #{endBalance}, #{submittedBy}, #{note}, COALESCE(#{sourceTag}, 'UNKNOWN'))
            ON DUPLICATE KEY UPDATE
                end_balance = VALUES(end_balance),
                submitted_by = VALUES(submitted_by),
                submitted_at = NOW(3),
                note = VALUES(note),
                source_tag = VALUES(source_tag)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int upsert(PeriodSnapshot snapshot);
}
