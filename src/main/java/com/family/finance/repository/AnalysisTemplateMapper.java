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
import java.util.Optional;

/** v1.27 · 我的分析模板(V64 · 内置模板不进库,见 BuiltinTemplates) */
@Mapper
public interface AnalysisTemplateMapper {

    /** 一行 = 一份完整副本 */
    class Row {
        public Long id;
        public Long familyId;
        public String name;
        public String sourceKey;
        public Integer sourceVersion;
        public String focus;
        public String stance;
        public String scope;
        public String anchor;
        public String extra;
        public Integer version;
        public Long updatedByMemberId;
        public LocalDateTime updatedAt;
    }

    String COLS = "id, family_id AS familyId, name, source_key AS sourceKey, source_version AS sourceVersion, "
            + "focus, stance, scope, anchor, extra, version, updated_by_member_id AS updatedByMemberId, "
            + "updated_at AS updatedAt";

    @Select("SELECT " + COLS + " FROM analysis_template WHERE family_id = #{familyId} ORDER BY id")
    List<Row> findByFamily(@Param("familyId") long familyId);

    @Select("SELECT " + COLS + " FROM analysis_template WHERE family_id = #{familyId} AND id = #{id}")
    Optional<Row> findById(@Param("familyId") long familyId, @Param("id") long id);

    @Select("SELECT COUNT(*) FROM analysis_template WHERE family_id = #{familyId}")
    int countByFamily(@Param("familyId") long familyId);

    @Insert("""
            INSERT INTO analysis_template (family_id, name, source_key, source_version, focus, stance,
                                           scope, anchor, extra, version, updated_by_member_id)
            VALUES (#{familyId}, #{name}, #{sourceKey}, #{sourceVersion}, #{focus}, #{stance},
                    #{scope}, #{anchor}, #{extra}, 1, #{updatedByMemberId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Row row);

    /** 保存一次版本 +1 —— 缓存键带版本,改了就不复用旧结论 */
    @Update("""
            UPDATE analysis_template
               SET name = #{name}, focus = #{focus}, stance = #{stance}, scope = #{scope},
                   anchor = #{anchor}, extra = #{extra}, version = version + 1,
                   updated_by_member_id = #{updatedByMemberId}
             WHERE id = #{id} AND family_id = #{familyId}
            """)
    int update(Row row);

    @Delete("DELETE FROM analysis_template WHERE id = #{id} AND family_id = #{familyId}")
    int delete(@Param("familyId") long familyId, @Param("id") long id);
}
