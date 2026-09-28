package com.family.finance.repository;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** v1.27 · 分析偏好(V64)· 只进 AI 提示词,不改任何数字 */
@Mapper
public interface AnalysisPreferenceMapper {

    class Row {
        public Long id;
        public Long familyId;
        public String content;
        public Boolean enabled;
        public String source;
        public Long createdByMemberId;
        public LocalDateTime createdAt;
    }

    @Select("""
            SELECT id, family_id AS familyId, content, enabled, source,
                   created_by_member_id AS createdByMemberId, created_at AS createdAt
              FROM analysis_preference
             WHERE family_id = #{familyId}
             ORDER BY id
            """)
    List<Row> findByFamily(@Param("familyId") long familyId);

    @Select("SELECT COUNT(*) FROM analysis_preference WHERE family_id = #{familyId}")
    int countByFamily(@Param("familyId") long familyId);

    @Insert("""
            INSERT INTO analysis_preference (family_id, content, enabled, source, created_by_member_id)
            VALUES (#{familyId}, #{content}, 1, #{source}, #{createdByMemberId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Row row);

    @Update("UPDATE analysis_preference SET enabled = #{enabled} WHERE id = #{id} AND family_id = #{familyId}")
    int setEnabled(@Param("familyId") long familyId, @Param("id") long id, @Param("enabled") boolean enabled);

    @Delete("DELETE FROM analysis_preference WHERE id = #{id} AND family_id = #{familyId}")
    int delete(@Param("familyId") long familyId, @Param("id") long id);
}
