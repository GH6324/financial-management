package com.family.finance.repository;

import com.family.finance.domain.ask.AskConversation;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * v1.19 · 会话表。
 *
 * <p>列名与字段名同名的用 {@code AS} 显式对齐驼峰,不依赖全局 map-underscore 设置 ——
 * 那个设置改一次,所有隐式依赖它的注解 mapper 一起哑掉,而且不报错、只是字段全 null。</p>
 */
@Mapper
public interface AskConversationMapper {

    /** 前后各留一个空格:注解 SQL 靠字符串拼接,少一个空格就粘成 {@code SELECTid} */
    String COLS = " id, family_id AS familyId, title, provider_ref AS providerRef,"
                + " ctx_period_id AS ctxPeriodId, ctx_currency AS ctxCurrency,"
                + " created_at AS createdAt, archived_at AS archivedAt ";

    @Insert("INSERT INTO ask_conversation (family_id, title, provider_ref, ctx_period_id, ctx_currency)"
          + " VALUES (#{familyId}, #{title}, #{providerRef}, #{ctxPeriodId}, #{ctxCurrency})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(AskConversation c);

    @Select("SELECT" + COLS + "FROM ask_conversation WHERE id = #{id}")
    AskConversation findById(@Param("id") long id);

    /** 最近的若干段(不含已归档);首页侧栏与历史列表共用 */
    @Select("SELECT" + COLS + "FROM ask_conversation"
          + " WHERE family_id = #{familyId} AND archived_at IS NULL"
          + " ORDER BY created_at DESC LIMIT #{limit}")
    List<AskConversation> recent(@Param("familyId") long familyId, @Param("limit") int limit);

    @Update("UPDATE ask_conversation SET provider_ref = #{providerRef} WHERE id = #{id}")
    void updateProviderRef(@Param("id") long id, @Param("providerRef") String providerRef);

    @Update("UPDATE ask_conversation SET title = #{title} WHERE id = #{id}")
    void updateTitle(@Param("id") long id, @Param("title") String title);

    @Update("UPDATE ask_conversation SET archived_at = NOW(3) WHERE id = #{id} AND family_id = #{familyId}")
    int archive(@Param("id") long id, @Param("familyId") long familyId);

    @Select("SELECT COUNT(*) FROM ask_conversation WHERE family_id = #{familyId} AND archived_at IS NULL")
    int countActive(@Param("familyId") long familyId);
}
