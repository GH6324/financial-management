package com.family.finance.repository;

import com.family.finance.domain.ask.AskToolCall;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * v1.19 · 工具调用摘要表。
 *
 * <p>v1.24 · 家庭隔离:本表没有 {@code family_id} 列,归属沿
 * {@code ask_tool_call → ask_message → ask_conversation.family_id} 取,每条语句自己走完。</p>
 */
@Mapper
public interface AskToolCallMapper {

    String COLS = " t.id, t.message_id AS messageId, t.tool_name AS toolName, t.args_json AS argsJson,"
                + " t.duration_ms AS durationMs, t.ok, t.summary ";

    @Insert("INSERT INTO ask_tool_call (message_id, tool_name, args_json, duration_ms, ok, summary)"
          + " SELECT #{t.messageId}, #{t.toolName}, #{t.argsJson}, #{t.durationMs}, #{t.ok}, #{t.summary}"
          + " FROM ask_message m JOIN ask_conversation v ON v.id = m.conversation_id"
          + " WHERE m.id = #{t.messageId} AND v.family_id = #{familyId}")
    @Options(useGeneratedKeys = true, keyProperty = "t.id")
    int insert(@Param("familyId") long familyId, @Param("t") AskToolCall t);

    /** 带归属断言的插入 —— 业务代码一律用这个 */
    default void insertOwned(long familyId, AskToolCall t) {
        if (insert(familyId, t) != 1) {
            throw new IllegalStateException("工具调用归属校验不通过:消息 " + t.getMessageId()
                    + " 不属于家庭 " + familyId);
        }
    }

    @Select("SELECT" + COLS + "FROM ask_tool_call t"
          + " JOIN ask_message m ON m.id = t.message_id"
          + " JOIN ask_conversation v ON v.id = m.conversation_id"
          + " WHERE v.family_id = #{familyId} AND t.message_id = #{messageId} ORDER BY t.id")
    List<AskToolCall> byMessage(@Param("familyId") long familyId, @Param("messageId") long messageId);

    @Select("SELECT" + COLS + "FROM ask_tool_call t"
          + " JOIN ask_message m ON m.id = t.message_id"
          + " JOIN ask_conversation v ON v.id = m.conversation_id"
          + " WHERE v.family_id = #{familyId} AND m.conversation_id = #{cid}"
          + " ORDER BY t.message_id, t.id")
    List<AskToolCall> byConversation(@Param("familyId") long familyId, @Param("cid") long conversationId);
}
