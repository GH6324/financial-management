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

    /**
     * 列清单。
     *
     * <p>刻意用普通字符串而不是文本块,前后各留一个空格 —— 文本块的首行紧跟 {@code """},
     * 于是 {@code "SELECT " + COLS} 会拼成 {@code SELECTid}。
     * 这个坑 {@code AskAccessTokenMapper} 的注释里早写着「真在 beta 上炸过」,
     * 而我在 v1.21 又踩了一次 —— 所以这一版补了护栏 {@code v1210-NO-TEXTBLOCK-COLS}。</p>
     */
    String COLS = " id, family_id AS familyId, parent_id AS parentId, name,"
                + " system_code AS systemCode, sort_order AS sortOrder,"
                + " expense_nature AS expenseNature,"
                + " archived_at AS archivedAt, created_at AS createdAt ";

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

    /**
     * v1.24 FR-612 · 改一个类目的支出性质。
     *
     * <p><b>「其他」也能改</b> —— 所以这里没有 {@code system_code IS NULL} 那一条。
     * 改名/停用/删除都要护着兜底项(它不能消失),但「其他」这一类到底算刚性还是弹性
     * 是用户家里的事实,没有理由不让他定。</p>
     *
     * <p>性质是<b>类目的属性</b>,所以改完<b>立即对全部历史生效</b>,
     * 不回写任何一行 {@code cash_flow}(选型一)。</p>
     *
     * <p>{@code nature} 传 null = 清回「继承父级 / 按弹性」。</p>
     */
    @Update("""
            UPDATE expense_category SET expense_nature = #{nature}
             WHERE id = #{id} AND family_id = #{familyId}
            """)
    int setNature(@Param("familyId") long familyId, @Param("id") long id,
                  @Param("nature") String nature);

    @Delete("DELETE FROM expense_category WHERE id = #{id} AND family_id = #{familyId} AND system_code IS NULL")
    int delete(@Param("familyId") long familyId, @Param("id") long id);

    @Delete("DELETE FROM expense_category WHERE parent_id = #{parentId} AND family_id = #{familyId}")
    int deleteChildren(@Param("familyId") long familyId, @Param("parentId") long parentId);

    @Select("SELECT COUNT(*) FROM expense_category WHERE family_id = #{familyId}")
    int countByFamily(@Param("familyId") long familyId);

    @Select("SELECT " + COLS
            + " FROM expense_category WHERE family_id = #{familyId} AND system_code = #{code}")
    ExpenseCategory findBySystemCode(@Param("familyId") long familyId, @Param("code") String code);
}
