package com.family.finance.repository;

import com.family.finance.domain.ask.AskToolCall;
import org.apache.ibatis.annotations.*;

import java.util.List;

/** v1.19 · 工具调用摘要表 */
@Mapper
public interface AskToolCallMapper {

    String COLS = " id, message_id AS messageId, tool_name AS toolName, args_json AS argsJson,"
                + " duration_ms AS durationMs, ok, summary ";

    @Insert("INSERT INTO ask_tool_call (message_id, tool_name, args_json, duration_ms, ok, summary)"
          + " VALUES (#{messageId}, #{toolName}, #{argsJson}, #{durationMs}, #{ok}, #{summary})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(AskToolCall t);

    @Select("SELECT" + COLS + "FROM ask_tool_call WHERE message_id = #{messageId} ORDER BY id")
    List<AskToolCall> byMessage(@Param("messageId") long messageId);

    @Select("SELECT" + COLS + "FROM ask_tool_call"
          + " WHERE message_id IN (SELECT id FROM ask_message WHERE conversation_id = #{cid})"
          + " ORDER BY message_id, id")
    List<AskToolCall> byConversation(@Param("cid") long conversationId);
}
