package com.family.finance.repository;

import com.family.finance.domain.expense.ExpenseSplit;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * v1.21 · 来源行。
 *
 * <p><b>只有 {@code ExpenseSplitService} 允许调用本 mapper 的写方法</b> ——
 * 因为每次写完都必须在同一事务里重算 Σ 回写 PMC。多一个写入口就多一条口径,
 * 而这个项目已经为「口径来源多一条」付过一次月均支出差 89% 的代价(见 ExpenseLedgerService v1.8 注释)。
 * 护栏 {@code v1210-SPLIT-SINGLE-WRITER} 钉着这一条。</p>
 */
@Mapper
public interface ExpenseSplitMapper {

    String COLS = """
            id, family_id AS familyId, period_id AS periodId, member_id AS memberId,
            category_id AS categoryId, source, amount, batch_id AS batchId,
            updated_at AS updatedAt
            """;

    @Insert("""
            INSERT INTO expense_split
                (family_id, period_id, member_id, category_id, source, amount, batch_id)
            VALUES
                (#{familyId}, #{periodId}, #{memberId}, #{categoryId}, #{source}, #{amount}, #{batchId})
            ON DUPLICATE KEY UPDATE amount = VALUES(amount), batch_id = VALUES(batch_id)
            """)
    int upsert(ExpenseSplit row);

    @Select("SELECT " + COLS + " FROM expense_split WHERE period_id = #{periodId} AND member_id = #{memberId}")
    List<ExpenseSplit> findByPeriodMember(@Param("periodId") long periodId, @Param("memberId") long memberId);

    @Select("SELECT " + COLS + " FROM expense_split WHERE family_id = #{familyId} AND period_id = #{periodId}")
    List<ExpenseSplit> findByPeriod(@Param("familyId") long familyId, @Param("periodId") long periodId);

    @Select("SELECT " + COLS + " FROM expense_split WHERE family_id = #{familyId}")
    List<ExpenseSplit> findByFamily(@Param("familyId") long familyId);

    /** 该(期×人)的合计 —— 回写 PMC 用的就是它。无行时返回 null,调用方判空 */
    @Select("""
            SELECT SUM(amount) FROM expense_split
             WHERE period_id = #{periodId} AND member_id = #{memberId}
            """)
    BigDecimal sumByPeriodMember(@Param("periodId") long periodId, @Param("memberId") long memberId);

    /** 重导某渠道:按(期×人×渠道)整批删,再插新的 —— 手填与其它渠道的行不在删除范围里 */
    @Delete("""
            DELETE FROM expense_split
             WHERE period_id = #{periodId} AND member_id = #{memberId} AND source = #{source}
            """)
    int deleteBySource(@Param("periodId") long periodId, @Param("memberId") long memberId,
                       @Param("source") String source);

    @Delete("DELETE FROM expense_split WHERE period_id = #{periodId} AND member_id = #{memberId}")
    int deleteByPeriodMember(@Param("periodId") long periodId, @Param("memberId") long memberId);

    /** 清掉一格里某个来源的行(手填改成 0 且没有渠道行时用) */
    @Delete("""
            DELETE FROM expense_split
             WHERE period_id = #{periodId} AND member_id = #{memberId}
               AND category_id = #{categoryId} AND source = #{source}
            """)
    int deleteOne(@Param("periodId") long periodId, @Param("memberId") long memberId,
                  @Param("categoryId") long categoryId, @Param("source") String source);

    /**
     * 清掉某个类目的所有来源行。
     *
     * <p>只在<b>搬迁之后</b>调用 —— 钱已经加到目标类目的行上了,这里删的是空壳。
     * 不提供「一条 UPDATE 改 category_id」的方法是刻意的:
     * {@code uk_split} 会在目标已有同来源行时撞键,那条 UPDATE 看着简洁,实际会抛异常。
     * 服务层必须走「合并同键 → 删源行」两步。</p>
     */
    @Delete("DELETE FROM expense_split WHERE family_id = #{familyId} AND category_id = #{categoryId}")
    int deleteByCategory(@Param("familyId") long familyId, @Param("categoryId") long categoryId);

    /**
     * 搬迁前的「会动多少数据」预告(FR-505 要在确认里说清楚)。
     * <p>注意 UPDATE 会撞 uk_split 唯一键(目标类目已有同来源行)——
     * 所以服务层不能裸调 moveCategory,要先合并同键行。</p>
     */
    @Select("""
            SELECT COUNT(DISTINCT period_id) FROM expense_split
             WHERE family_id = #{familyId} AND category_id = #{categoryId}
            """)
    int countPeriodsUsing(@Param("familyId") long familyId, @Param("categoryId") long categoryId);

    @Select("""
            SELECT COUNT(*) FROM expense_split
             WHERE family_id = #{familyId} AND category_id = #{categoryId}
            """)
    int countRowsUsing(@Param("familyId") long familyId, @Param("categoryId") long categoryId);

    /** 家庭全期合计 —— 「删类目不丢钱」「切深度无损」两条护栏的判据 */
    @Select("SELECT IFNULL(SUM(amount),0) FROM expense_split WHERE family_id = #{familyId}")
    BigDecimal sumByFamily(@Param("familyId") long familyId);
}
