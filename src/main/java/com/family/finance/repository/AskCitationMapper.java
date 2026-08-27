package com.family.finance.repository;

import com.family.finance.domain.ask.AskCitation;
import org.apache.ibatis.annotations.*;

import java.util.List;

/** v1.19 · 引用块表 */
@Mapper
public interface AskCitationMapper {

    String COLS = " id, message_id AS messageId, cite_key AS citeKey, metric_key AS metricKey,"
                + " label, period_id AS periodId, in_progress AS inProgress, value_text AS valueText,"
                + " currency, target_href AS targetHref ";

    @Insert("INSERT INTO ask_citation (message_id, cite_key, metric_key, label, period_id, in_progress,"
          + " value_text, currency, target_href)"
          + " VALUES (#{messageId}, #{citeKey}, #{metricKey}, #{label}, #{periodId}, #{inProgress},"
          + " #{valueText}, #{currency}, #{targetHref})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(AskCitation c);

    @Select("SELECT" + COLS + "FROM ask_citation WHERE message_id = #{messageId} ORDER BY id")
    List<AskCitation> byMessage(@Param("messageId") long messageId);

    /** 整段会话一次取完,避免按消息 N+1 */
    @Select("SELECT" + COLS + "FROM ask_citation"
          + " WHERE message_id IN (SELECT id FROM ask_message WHERE conversation_id = #{cid})"
          + " ORDER BY message_id, id")
    List<AskCitation> byConversation(@Param("cid") long conversationId);
}
