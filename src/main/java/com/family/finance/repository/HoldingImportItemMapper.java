package com.family.finance.repository;

import com.family.finance.domain.holdingimport.HoldingImportItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * holding_import_item 表 Mapper · v1.4。
 */
@Mapper
public interface HoldingImportItemMapper {

    String COLS = """
            id, import_id AS importId, parsed_name AS parsedName, parsed_code AS parsedCode,
            market_value AS marketValue, confidence, match_state AS matchState, matched_hid AS matchedHid,
            old_value AS oldValue, asset_class_tag AS assetClassTag, industry_tag AS industryTag,
            platform_tag AS platformTag, shot_path AS shotPath, user_decision AS userDecision,
            selected, sort_no AS sortNo
            """;

    @Select("SELECT " + COLS + " FROM holding_import_item WHERE import_id = #{importId} ORDER BY sort_no, id")
    List<HoldingImportItem> findByImport(@Param("importId") long importId);

    @Select("SELECT " + COLS + " FROM holding_import_item WHERE id = #{id}")
    java.util.Optional<HoldingImportItem> findById(@Param("id") long id);

    @Insert("""
            INSERT INTO holding_import_item (import_id, parsed_name, parsed_code, market_value, confidence,
                        match_state, matched_hid, old_value, asset_class_tag, industry_tag, platform_tag,
                        shot_path, user_decision, selected, sort_no)
            VALUES (#{importId}, #{parsedName}, #{parsedCode}, #{marketValue}, #{confidence},
                    #{matchState}, #{matchedHid}, #{oldValue}, #{assetClassTag}, #{industryTag}, #{platformTag},
                    #{shotPath}, #{userDecision}, #{selected}, #{sortNo})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(HoldingImportItem item);

    /** 用户在确认表里编辑后回写一项(名称/市值/标签/匹配/勾选/定夺) */
    @Update("""
            UPDATE holding_import_item
               SET parsed_name = #{parsedName}, market_value = #{marketValue},
                   match_state = #{matchState}, matched_hid = #{matchedHid},
                   asset_class_tag = #{assetClassTag}, industry_tag = #{industryTag}, platform_tag = #{platformTag},
                   user_decision = #{userDecision}, selected = #{selected}
             WHERE id = #{id}
            """)
    int update(HoldingImportItem item);

    @Delete("DELETE FROM holding_import_item WHERE import_id = #{importId}")
    int deleteByImport(@Param("importId") long importId);
}
