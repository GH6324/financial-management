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

    /**
     * @deprecated v1.23 起**不要再新增调用**。双活跃窗口(上期与新期同时 OPEN)下,
     *     这个方法**静默返回最新那期,不报错、不告警** —— 于是漏审计的调用点
     *     不会崩,只会给出一个看起来合理但是错的数(prd/v1.23.md §1.1)。
     *     按语义改用:
     *     <ul>
     *       <li>{@link #findBalancePeriod(long)} —— 余额 / 估值轴(只有一个「现在」)</li>
     *       <li>{@link #findRecordableOpen(long)} —— 收支轴(可以有两个活跃期)</li>
     *     </ul>
     *     保留它是因为 30 处存量调用点里 D 类(管理 / 通知)语义上确实是「随便哪个 OPEN 都行」,
     *     强行改名反而丢失「这里没想清楚」的信息。护栏 {@code v1230-DUAL-OPEN-SWEEP}
     *     拦住调用数上涨。
     */
    @Deprecated
    @Select("""
            SELECT id, family_id, period_type, period_start, period_end, status, closed_at, created_at
              FROM period
             WHERE family_id = #{familyId}
               AND status = 'OPEN'
             ORDER BY period_start DESC
             LIMIT 1
            """)
    Optional<Period> findCurrentOpen(@Param("familyId") long familyId);

    /**
     * v1.23 · **余额 / 估值轴的当前期** = 最新 OPEN 期。
     *
     * <p>世界上只能有一个「现在的市值 / 余额 / 汇率」,所以估值刷新、持仓变动、券商同步、
     * 截图导入的余额写入**永远落这一期**,绝不回写仍在宽限窗口里的补录期。</p>
     *
     * <p>SQL 与 {@link #findCurrentOpen} 完全相同 —— 差别只在名字。
     * 而名字就是目的:以后有人写 {@code findBalancePeriod} 会停一秒想
     * 「我要的是余额期吗」,写 {@code findCurrentOpen} 不会。</p>
     */
    @Select("""
            SELECT id, family_id, period_type, period_start, period_end, status, closed_at, created_at
              FROM period
             WHERE family_id = #{familyId}
               AND status = 'OPEN'
             ORDER BY period_start DESC
             LIMIT 1
            """)
    Optional<Period> findBalancePeriod(@Param("familyId") long familyId);

    /**
     * v1.23 · **收支轴的可写期** = 全部 OPEN 期,**升序**(老的在前)。
     *
     * <p>双活跃窗口下这里返回两条:[补录期, 进行期]。升序是刻意的 ——
     * 宽限期内用户打开填报页八成是为了填上个月,取 {@code getFirst()} 正好是补录期(FR-625)。</p>
     *
     * <p>正常情况(T+0 或宽限外)只返回一条,{@code getFirst() == getLast()},调用方无需分支。
     * 返回 0 条 = 这个家庭当前没有可写期(关账后还没开新期),调用方要显式处理。</p>
     */
    @Select("""
            SELECT id, family_id, period_type, period_start, period_end, status, closed_at, created_at
              FROM period
             WHERE family_id = #{familyId}
               AND status = 'OPEN'
             ORDER BY period_start ASC
            """)
    List<Period> findRecordableOpen(@Param("familyId") long familyId);

    /** v0.11.2 · 开新期时找出所有「早于新期起始日」且仍 OPEN 的旧期(用于滚动自动关账);升序,先关最老的。 */
    @Select("""
            SELECT id, family_id, period_type, period_start, period_end, status, closed_at, created_at
              FROM period
             WHERE family_id = #{familyId}
               AND status = 'OPEN'
               AND period_start < #{periodStart}
             ORDER BY period_start ASC
            """)
    List<Period> findOpenBefore(@Param("familyId") long familyId, @Param("periodStart") java.time.LocalDate periodStart);

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
     * v1.8 · 近 N 期,但**不超过 asOf**。
     *
     * <p>不能直接用 {@link #findLatest}:账期表可能预建到很多年以后(beta 就排到了 2038),
     * 按 period_start 倒序取「最近 1 期」会取到未来的空期,支出构成直接空图。</p>
     */
    @Select("""
            SELECT id, family_id, period_type, period_start, period_end, status, closed_at, created_at
              FROM period
             WHERE family_id = #{familyId}
               AND period_start <= #{asOf}
             ORDER BY period_start DESC
             LIMIT #{limit}
            """)
    List<Period> findRecentAsOf(@Param("familyId") long familyId,
                                @Param("asOf") java.time.LocalDate asOf,
                                @Param("limit") int limit);

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

    /**
     * v1.23 §4.4 · 最近一个**已定稿**账期(≤ asOf)· 收益类指标的锚点。
     *
     * <p>「已定稿」= 下面两种之一:</p>
     * <ol>
     *   <li>{@code status = 'CLOSED'} —— 老判据,不变</li>
     *   <li><b>已自然结束(period_end &lt; asOf)且填报完成</b> —— 本版新增</li>
     * </ol>
     *
     * <p><b>为什么要加第 2 种</b>:{@link #findLatestClosedAsOf} 只认 CLOSED。
     * 双活跃窗口里补录期还没关,于是收益类指标(本月资产收益 / XIRR / TWR / 基准对比 /
     * 钱赚)会**集体锚回上上期** —— 报表页看起来「少了一个月」,而 dashboard 一起退。
     * 顺带修了一个既有缺陷:今天一个填完但还没到 00:30 的期同样不被采纳,
     * 只是那个窗口只有几小时,没人注意到。</p>
     *
     * <p><b>判据必须是「填完了没有」而不是「关了没有」</b>:如果用户压根没填,
     * 补录期就是真的半填,锚它会把「还没录的收支」整个算成投资收益
     * (v1.6.30 那次 prod 事故就是这么来的)。所以第 2 种的两个条件一个都不能少。</p>
     *
     * <p>「填报完成」判据与 {@code PeriodService.markCompletedByMember} 触发自动关账的条件
     * <b>同源</b>:无 PENDING todo + 全体活跃成员已提交。
     * {@code FactMapper.findSettledPeriodIds} 用的是同一段逻辑 ——
     * 两处必须一致,护栏 {@code v1230-SETTLED-JUDGE-ALIGNED} 守这件事。</p>
     */
    @Select("""
            SELECT p.id, p.family_id, p.period_type, p.period_start, p.period_end,
                   p.status, p.closed_at, p.created_at
              FROM period p
             WHERE p.family_id = #{familyId}
               AND p.period_start <= #{asOf}
               AND (p.status = 'CLOSED'
                    OR (p.period_end < #{asOf}
                        AND NOT EXISTS (SELECT 1 FROM snapshot_todo t
                                         WHERE t.period_id = p.id AND t.status = 'PENDING')
                        AND (SELECT COUNT(*) FROM period_member_completion c
                              WHERE c.period_id = p.id)
                            >= (SELECT COUNT(*) FROM member m
                                 WHERE m.family_id = p.family_id AND m.archived_at IS NULL)))
             ORDER BY p.period_start DESC
             LIMIT 1
            """)
    Optional<Period> findLatestSettledAsOf(@Param("familyId") long familyId,
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
