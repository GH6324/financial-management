package com.family.finance.repository;

import com.family.finance.domain.period.PeriodMemberCashflow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * period_member_cashflow 表 Mapper · v0.3 FR-51 修订。
 *
 * <p>成员级填报 · 家庭聚合走 SUM by period。</p>
 */
@Mapper
public interface PeriodMemberCashflowMapper {

    @Select("""
            SELECT id, family_id, period_id, member_id, total_income_input, total_expense_input,
                   created_at, updated_at
              FROM period_member_cashflow
             WHERE period_id = #{periodId}
               AND member_id = #{memberId}
            """)
    Optional<PeriodMemberCashflow> findByPeriodAndMember(@Param("periodId") long periodId,
                                                         @Param("memberId") long memberId);

    @Select("""
            SELECT id, family_id, period_id, member_id, total_income_input, total_expense_input,
                   created_at, updated_at
              FROM period_member_cashflow
             WHERE period_id = #{periodId}
             ORDER BY member_id
            """)
    List<PeriodMemberCashflow> findByPeriod(@Param("periodId") long periodId);

    @Insert("""
            INSERT INTO period_member_cashflow
                (family_id, period_id, member_id, total_income_input, total_expense_input)
            VALUES
                (#{familyId}, #{periodId}, #{memberId}, #{totalIncomeInput}, #{totalExpenseInput})
            ON DUPLICATE KEY UPDATE
                total_income_input = VALUES(total_income_input),
                total_expense_input = VALUES(total_expense_input)
            """)
    int upsert(PeriodMemberCashflow row);

    /**
     * 家庭级聚合 · SUM 跨成员 · 给"近 N 期收入/支出总和"用。
     * 一期一行 · 任一成员 NOT NULL 即返回该期(NULL 视为 0)。
     */
    @Select("""
            SELECT pmc.period_id           AS periodId,
                   p.period_start          AS periodStart,
                   SUM(IFNULL(pmc.total_income_input, 0))  AS totalIncome,
                   SUM(IFNULL(pmc.total_expense_input, 0)) AS totalExpense,
                   SUM(CASE WHEN pmc.total_income_input IS NOT NULL OR pmc.total_expense_input IS NOT NULL THEN 1 ELSE 0 END) AS filledMembers
              FROM period_member_cashflow pmc
              JOIN period p ON p.id = pmc.period_id
             WHERE pmc.family_id = #{familyId}
             GROUP BY pmc.period_id, p.period_start
            HAVING filledMembers > 0
             ORDER BY p.period_start DESC
             LIMIT #{limit}
            """)
    List<FamilyPeriodAggregate> findFamilyAggregateRecent(@Param("familyId") long familyId,
                                                          @Param("limit") int limit);

    /**
     * 家庭聚合值对象 · period 级 income/expense 总和。
     */
    record FamilyPeriodAggregate(
        Long periodId,
        java.time.LocalDate periodStart,
        BigDecimal totalIncome,
        BigDecimal totalExpense,
        Integer filledMembers
    ) {}

    /**
     * 指定 period 的家庭单期 SUM(实时查询 · 给 entry 页面"家庭已填:N 人 · 总收入 ¥X"用)。
     */
    @Select("""
            SELECT pmc.period_id           AS periodId,
                   #{periodId}             AS dummy,
                   SUM(IFNULL(pmc.total_income_input, 0))  AS totalIncome,
                   SUM(IFNULL(pmc.total_expense_input, 0)) AS totalExpense,
                   COUNT(*)                AS filledMembers
              FROM period_member_cashflow pmc
             WHERE pmc.period_id = #{periodId}
               AND (pmc.total_income_input IS NOT NULL OR pmc.total_expense_input IS NOT NULL)
            """)
    Optional<SinglePeriodAggregate> findFamilyAggregateForPeriod(@Param("periodId") long periodId);

    /**
     * v1.12 FR-352 · {@link #findFamilyAggregateForPeriod} 的批量版。
     * 过滤条件与点查<b>逐字相同</b>,只是把 {@code period_id = ?} 换成 {@code IN (...)} 并按期分组。
     *
     * <p><b>有一处语义差,调用方必须自己补上</b>:点查版没有 {@code GROUP BY},
     * 即使一行都不匹配也会返回一行(各 SUM 为 NULL、{@code filledMembers = 0});
     * 批量版按期分组,<b>没有匹配行的期直接不出现在结果里</b>。
     * 对下游是同一件事(「该期没有手填收支」),但代码上「缺 key」与「有 key 值为 NULL」是两回事 ——
     * 所以调用方(ExpenseLedgerService / FactViewServiceImpl)必须把这两种情况当同一种处理。
     *
     * <p>别拿 {@link #findFamilyAggregateRecent} 代替它:那条带
     * {@code HAVING filledMembers > 0} 和 {@code LIMIT},是<b>另一个口径</b>
     * (见 ExpenseLedgerService 类注释「连带影响二」,当年换错过一次,4 条单测立刻挂)。
     */
    @Select("""
            <script>
            SELECT pmc.period_id           AS periodId,
                   pmc.period_id           AS dummy,
                   SUM(IFNULL(pmc.total_income_input, 0))  AS totalIncome,
                   SUM(IFNULL(pmc.total_expense_input, 0)) AS totalExpense,
                   COUNT(*)                AS filledMembers
              FROM period_member_cashflow pmc
             WHERE pmc.period_id IN
                   <foreach collection="periodIds" item="pid" open="(" separator="," close=")">#{pid}</foreach>
               AND (pmc.total_income_input IS NOT NULL OR pmc.total_expense_input IS NOT NULL)
             GROUP BY pmc.period_id
            </script>
            """)
    List<SinglePeriodAggregate> findFamilyAggregateForPeriods(
            @Param("periodIds") java.util.Collection<Long> periodIds);

    record SinglePeriodAggregate(
        Long periodId,
        Long dummy,
        BigDecimal totalIncome,
        BigDecimal totalExpense,
        Integer filledMembers
    ) {}
}
