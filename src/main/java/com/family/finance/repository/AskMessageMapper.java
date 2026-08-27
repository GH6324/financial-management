package com.family.finance.repository;

import com.family.finance.domain.ask.AskMessage;
import org.apache.ibatis.annotations.*;

import java.util.List;

/** v1.19 · 消息表 */
@Mapper
public interface AskMessageMapper {

    String COLS = " id, conversation_id AS conversationId, role, content_text AS contentText,"
                + " seq, created_at AS createdAt ";

    @Insert("INSERT INTO ask_message (conversation_id, role, content_text, seq)"
          + " VALUES (#{conversationId}, #{role}, #{contentText}, #{seq})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(AskMessage m);

    @Select("SELECT" + COLS + "FROM ask_message WHERE conversation_id = #{cid} ORDER BY seq")
    List<AskMessage> byConversation(@Param("cid") long conversationId);

    /** 下一个序号 —— 单家庭单机,COALESCE(MAX)+1 够用,不引锁 */
    @Select("SELECT COALESCE(MAX(seq), 0) + 1 FROM ask_message WHERE conversation_id = #{cid}")
    int nextSeq(@Param("cid") long conversationId);

    /** 送进模型上下文的历史:只取 user/assistant,旁白不算(它是给人看的) */
    @Select("SELECT" + COLS + "FROM ask_message"
          + " WHERE conversation_id = #{cid} AND role IN ('user','assistant')"
          + " ORDER BY seq DESC LIMIT #{limit}")
    List<AskMessage> recentForContext(@Param("cid") long conversationId, @Param("limit") int limit);

    @Update("UPDATE ask_message SET content_text = #{contentText} WHERE id = #{id}")
    void updateContent(@Param("id") long id, @Param("contentText") String contentText);
}
