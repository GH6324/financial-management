package com.family.finance.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** v1.2 · AI 月度复盘缓存(V48 · UNIQUE(family,period,dim) 覆盖写) */
@Mapper
public interface ReviewAiCacheMapper {

    record Row(Long id, Long familyId, Long periodId, String dim, String text, String vendor) {}

    @Select("SELECT id, family_id AS familyId, period_id AS periodId, dim, text, vendor FROM review_ai_cache WHERE family_id=#{familyId} AND period_id=#{periodId} AND dim=#{dim}")
    Row find(@Param("familyId") long familyId, @Param("periodId") long periodId, @Param("dim") String dim);

    @Insert("""
            INSERT INTO review_ai_cache (family_id, period_id, dim, text, vendor)
            VALUES (#{familyId}, #{periodId}, #{dim}, #{text}, #{vendor})
            ON DUPLICATE KEY UPDATE text=VALUES(text), vendor=VALUES(vendor), created_at=CURRENT_TIMESTAMP
            """)
    int upsert(@Param("familyId") long familyId, @Param("periodId") long periodId,
               @Param("dim") String dim, @Param("text") String text, @Param("vendor") String vendor);
}
