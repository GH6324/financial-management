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
            it.id, it.import_id AS importId, it.parsed_name AS parsedName, it.parsed_code AS parsedCode,
            it.market_value AS marketValue, it.confidence, it.match_state AS matchState,
            it.matched_hid AS matchedHid, it.old_value AS oldValue,
            it.asset_class_tag AS assetClassTag, it.industry_tag AS industryTag,
            it.platform_tag AS platformTag, it.shot_path AS shotPath, it.user_decision AS userDecision,
            it.selected, it.sort_no AS sortNo
            """;

    /** 归属链:item → holding_import.family_id(那张表自己有 family_id 列) */
    String OWNED = " JOIN holding_import imp ON imp.id = it.import_id";

    @Select("SELECT " + COLS + " FROM holding_import_item it" + OWNED
          + " WHERE imp.family_id = #{familyId} AND it.import_id = #{importId}"
          + " ORDER BY it.sort_no, it.id")
    List<HoldingImportItem> findByImport(@Param("familyId") long familyId,
                                         @Param("importId") long importId);

    @Select("SELECT " + COLS + " FROM holding_import_item it" + OWNED
          + " WHERE imp.family_id = #{familyId} AND it.id = #{id}")
    java.util.Optional<HoldingImportItem> findById(@Param("familyId") long familyId,
                                                   @Param("id") long id);

    @Insert("""
            INSERT INTO holding_import_item (import_id, parsed_name, parsed_code, market_value, confidence,
                        match_state, matched_hid, old_value, asset_class_tag, industry_tag, platform_tag,
                        shot_path, user_decision, selected, sort_no)
            SELECT #{it.importId}, #{it.parsedName}, #{it.parsedCode}, #{it.marketValue}, #{it.confidence},
                   #{it.matchState}, #{it.matchedHid}, #{it.oldValue}, #{it.assetClassTag},
                   #{it.industryTag}, #{it.platformTag},
                   #{it.shotPath}, #{it.userDecision}, #{it.selected}, #{it.sortNo}
              FROM holding_import imp
             WHERE imp.id = #{it.importId} AND imp.family_id = #{familyId}
            """)
    @Options(useGeneratedKeys = true, keyProperty = "it.id")
    int insert(@Param("familyId") long familyId, @Param("it") HoldingImportItem item);

    /** 带归属断言的插入 —— 业务代码一律用这个 */
    default void insertOwned(long familyId, HoldingImportItem item) {
        if (insert(familyId, item) != 1) {
            throw new IllegalStateException("导入项归属校验不通过:导入 " + item.getImportId()
                    + " 不属于家庭 " + familyId);
        }
    }

    /** 用户在确认表里编辑后回写一项(名称/市值/标签/匹配/勾选/定夺) */
    @Update("""
            UPDATE holding_import_item it
              JOIN holding_import imp ON imp.id = it.import_id
               SET it.parsed_name = #{it.parsedName}, it.market_value = #{it.marketValue},
                   it.match_state = #{it.matchState}, it.matched_hid = #{it.matchedHid},
                   it.asset_class_tag = #{it.assetClassTag}, it.industry_tag = #{it.industryTag},
                   it.platform_tag = #{it.platformTag},
                   it.user_decision = #{it.userDecision}, it.selected = #{it.selected}
             WHERE imp.family_id = #{familyId}
               AND it.id = #{it.id}
            """)
    int update(@Param("familyId") long familyId, @Param("it") HoldingImportItem item);

    @Delete("DELETE it FROM holding_import_item it" + OWNED
          + " WHERE imp.family_id = #{familyId} AND it.import_id = #{importId}")
    int deleteByImport(@Param("familyId") long familyId, @Param("importId") long importId);
}
