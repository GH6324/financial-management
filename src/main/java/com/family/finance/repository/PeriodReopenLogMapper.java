package com.family.finance.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PeriodReopenLogMapper {

    /**
     * 记一次重开账。
     *
     * <p>v1.24 · 家庭隔离:{@code period_reopen_log} 没有 {@code family_id} 列,
     * 归属沿 {@code → period.family_id} 取。这是审计痕迹 —— 写进别人家的账期日志
     * 既污染了对方的审计,也让本家的那次重开无迹可寻。
     * 归属不符 = 影响行数 0,调用方必须当错处理。</p>
     */
    @Insert("""
            INSERT INTO period_reopen_log (period_id, reopened_by, reason)
            SELECT #{periodId}, #{reopenedBy}, #{reason}
              FROM period p
             WHERE p.id = #{periodId} AND p.family_id = #{familyId}
            """)
    int insert(@Param("familyId") long familyId,
               @Param("periodId") long periodId,
               @Param("reopenedBy") Long reopenedBy,
               @Param("reason") String reason);

    /** 带归属断言的写入 —— 业务代码一律用这个 */
    default void insertOwned(long familyId, long periodId, Long reopenedBy, String reason) {
        if (insert(familyId, periodId, reopenedBy, reason) != 1) {
            throw new IllegalStateException("重开日志归属校验不通过:账期 " + periodId
                    + " 不属于家庭 " + familyId);
        }
    }
}
