package com.family.finance.repository;

import com.family.finance.domain.expense.ExpenseCategory;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** v1.21 · 支出类目树。树最多 40 行,一律整棵读回内存组装,不做递归 SQL。 */
@Mapper
public interface ExpenseCategoryMapper {

    String COLS = """
            id, family_id AS familyId, parent_id AS parentId, name,
            system_code AS systemCode, sort_order AS sortOrder,
            archived_at AS archivedAt, created_at AS createdAt
            """;

    @Insert("""
            INSERT INTO expense_category (family_id, parent_id, name, system_code, sort_order)
            VALUES (#{familyId}, #{parentId}, #{name}, #{systemCode}, #{sortOrder})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ExpenseCategory c);

    @Select("SELECT " + COLS + " FROM expense_category WHERE family_id = #{familyId} ORDER BY sort_order, id")
    List<ExpenseCategory> findByFamily(@Param("familyId") long familyId);

    @Select("SELECT " + COLS + " FROM expense_category WHERE id = #{id} AND family_id = #{familyId}")
    ExpenseCategory find(@Param("familyId") long familyId, @Param("id") long id);

    /** 只改名字与排序 —— parentId 不给改:挪动归属会让历史数据的父级合计跳变 */
    @Update("""
            UPDATE expense_category SET name = #{name}, sort_order = #{sortOrder}
             WHERE id = #{id} AND family_id = #{familyId} AND system_code IS NULL
            """)
    int rename(ExpenseCategory c);

    /** 停用/恢复。system_code 非空(即「其他」)一律不动 —— 兜底项不能消失 */
    @Update("""
            UPDATE expense_category SET archived_at = #{archived} ? CURRENT_TIMESTAMP(3) : NULL
             WHERE id = #{id} AND family_id = #{familyId} AND system_code IS NULL
            """)
    int setArchived(@Param("familyId") long familyId, @Param("id") long id,
                    @Param("archived") boolean archived);

    /** 一级停用时,其下二级一并停用(否则细类会挂在一个不存在的大类下) */
    @Update("""
            UPDATE expense_category SET archived_at = #{archived} ? CURRENT_TIMESTAMP(3) : NULL
             WHERE parent_id = #{parentId} AND family_id = #{familyId} AND system_code IS NULL
            """)
    int setArchivedByParent(@Param("familyId") long familyId, @Param("parentId") long parentId,
                            @Param("archived") boolean archived);

    @Delete("DELETE FROM expense_category WHERE id = #{id} AND family_id = #{familyId} AND system_code IS NULL")
    int delete(@Param("familyId") long familyId, @Param("id") long id);

    @Delete("DELETE FROM expense_category WHERE parent_id = #{parentId} AND family_id = #{familyId}")
    int deleteChildren(@Param("familyId") long familyId, @Param("parentId") long parentId);

    @Select("SELECT COUNT(*) FROM expense_category WHERE family_id = #{familyId}")
    int countByFamily(@Param("familyId") long familyId);

    @Select("""
            SELECT """ + COLS + """
              FROM expense_category
             WHERE family_id = #{familyId} AND system_code = #{code}
            """)
    ExpenseCategory findBySystemCode(@Param("familyId") long familyId, @Param("code") String code);
}
