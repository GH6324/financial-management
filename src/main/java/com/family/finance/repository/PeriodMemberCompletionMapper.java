package com.family.finance.repository;

import com.family.finance.domain.period.PeriodMemberCompletion;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Delete;

import java.util.List;

@Mapper
public interface PeriodMemberCompletionMapper {

    @Select("SELECT COUNT(*) FROM period_member_completion c"
          + " JOIN period p ON p.id = c.period_id"
          + " WHERE p.family_id = #{familyId} AND c.period_id = #{periodId}")
    int countByPeriod(@Param("familyId") long familyId, @Param("periodId") long periodId);

    /** v0.4.14 FR-63c · 本期已提交完成的成员 id 列表(调度器据此算"谁还没填") */
    @Select("SELECT c.member_id FROM period_member_completion c"
          + " JOIN period p ON p.id = c.period_id"
          + " WHERE p.family_id = #{familyId} AND c.period_id = #{periodId}")
    List<Long> findCompletedMemberIds(@Param("familyId") long familyId,
                                      @Param("periodId") long periodId);

    /**
     * 记一条「这个成员本期填完了」。
     *
     * <p>v1.24 · 家庭隔离:账期与成员必须同属这个家。{@code INSERT IGNORE} 保留 ——
     * 重复提交是幂等的;但这让「影响行数 0」有两种含义(已存在 / 归属不符),
     * 所以这里<b>不配 insertOwned 断言</b>,而是靠 SQL 本身拦住跨家庭写入。</p>
     */
    @Insert("""
            INSERT IGNORE INTO period_member_completion (period_id, member_id)
            SELECT #{c.periodId}, #{c.memberId}
              FROM period p
              JOIN member m ON m.id = #{c.memberId}
             WHERE p.id = #{c.periodId}
               AND p.family_id = #{familyId}
               AND m.family_id = #{familyId}
            """)
    @Options(useGeneratedKeys = true, keyProperty = "c.id")
    int insertIgnore(@Param("familyId") long familyId, @Param("c") PeriodMemberCompletion completion);

    @Delete("DELETE c FROM period_member_completion c"
          + " JOIN period p ON p.id = c.period_id"
          + " WHERE p.family_id = #{familyId} AND c.period_id = #{periodId}")
    int deleteByPeriod(@Param("familyId") long familyId, @Param("periodId") long periodId);
}
