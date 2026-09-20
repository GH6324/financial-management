package com.family.finance.repository;

import com.family.finance.domain.ask.AskCitation;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * v1.19 · 引用块表。
 *
 * <p>v1.24 · 家庭隔离:本表没有 {@code family_id} 列,归属沿
 * {@code ask_citation → ask_message → ask_conversation.family_id} 这条链取。
 * 每条语句都自己走完这条链 —— 不接受「调用方已经校验过 messageId 是自家的」,
 * 调用方会变,SQL 不会跟着变。</p>
 */
@Mapper
public interface AskCitationMapper {

    String COLS = " c.id, c.message_id AS messageId, c.cite_key AS citeKey, c.metric_key AS metricKey,"
                + " c.label, c.period_id AS periodId, c.in_progress AS inProgress, c.value_text AS valueText,"
                + " c.currency, c.target_href AS targetHref ";

    /** 归属链:message → conversation。不属于这个家就插 0 行,由 {@link #insertOwned} 抛出来。 */
    @Insert("INSERT INTO ask_citation (message_id, cite_key, metric_key, label, period_id, in_progress,"
          + " value_text, currency, target_href)"
          + " SELECT #{c.messageId}, #{c.citeKey}, #{c.metricKey}, #{c.label}, #{c.periodId}, #{c.inProgress},"
          + " #{c.valueText}, #{c.currency}, #{c.targetHref}"
          + " FROM ask_message m JOIN ask_conversation v ON v.id = m.conversation_id"
          + " WHERE m.id = #{c.messageId} AND v.family_id = #{familyId}")
    @Options(useGeneratedKeys = true, keyProperty = "c.id")
    int insert(@Param("familyId") long familyId, @Param("c") AskCitation c);

    /** 带归属断言的插入 —— 业务代码一律用这个 */
    default void insertOwned(long familyId, AskCitation c) {
        if (insert(familyId, c) != 1) {
            throw new IllegalStateException("引用归属校验不通过:消息 " + c.getMessageId()
                    + " 不属于家庭 " + familyId);
        }
    }

    @Select("SELECT" + COLS + "FROM ask_citation c"
          + " JOIN ask_message m ON m.id = c.message_id"
          + " JOIN ask_conversation v ON v.id = m.conversation_id"
          + " WHERE v.family_id = #{familyId} AND c.message_id = #{messageId} ORDER BY c.id")
    List<AskCitation> byMessage(@Param("familyId") long familyId, @Param("messageId") long messageId);

    /** 整段会话一次取完,避免按消息 N+1 */
    @Select("SELECT" + COLS + "FROM ask_citation c"
          + " JOIN ask_message m ON m.id = c.message_id"
          + " JOIN ask_conversation v ON v.id = m.conversation_id"
          + " WHERE v.family_id = #{familyId} AND m.conversation_id = #{cid}"
          + " ORDER BY c.message_id, c.id")
    List<AskCitation> byConversation(@Param("familyId") long familyId, @Param("cid") long conversationId);
}
