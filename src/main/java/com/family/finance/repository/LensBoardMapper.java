package com.family.finance.repository;

import com.family.finance.domain.lens.LensBoard;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** v1.1 · 用户自存透视看板 · lens_board */
@Mapper
public interface LensBoardMapper {

    @Select("""
            SELECT id, family_id, name, spec_json, display_order, created_at
              FROM lens_board
             WHERE family_id = #{familyId}
             ORDER BY display_order, id
            """)
    List<LensBoard> findByFamily(@Param("familyId") long familyId);

    @Insert("""
            INSERT INTO lens_board (family_id, name, spec_json, display_order)
            VALUES (#{familyId}, #{name}, #{specJson}, #{displayOrder})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(LensBoard board);

    @Delete("DELETE FROM lens_board WHERE id = #{id} AND family_id = #{familyId}")
    int delete(@Param("familyId") long familyId, @Param("id") long id);
}
