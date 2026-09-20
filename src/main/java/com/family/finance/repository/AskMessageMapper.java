package com.family.finance.repository;

import com.family.finance.domain.ask.AskMessage;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * v1.19 · 消息表。
 *
 * <p>v1.24 · 家庭隔离:本表没有 {@code family_id} 列,归属沿
 * {@code ask_message → ask_conversation.family_id} 取,每条语句自己走完这条 JOIN。</p>
 */
@Mapper
public interface AskMessageMapper {

    String COLS = " m.id, m.conversation_id AS conversationId, m.role, m.content_text AS contentText,"
                + " m.seq, m.created_at AS createdAt ";

    @Insert("INSERT INTO ask_message (conversation_id, role, content_text, seq)"
          + " SELECT #{m.conversationId}, #{m.role}, #{m.contentText}, #{m.seq}"
          + " FROM ask_conversation v"
          + " WHERE v.id = #{m.conversationId} AND v.family_id = #{familyId}")
    @Options(useGeneratedKeys = true, keyProperty = "m.id")
    int insert(@Param("familyId") long familyId, @Param("m") AskMessage m);

    /** 带归属断言的插入 —— 业务代码一律用这个 */
    default void insertOwned(long familyId, AskMessage m) {
        if (insert(familyId, m) != 1) {
            throw new IllegalStateException("消息归属校验不通过:会话 " + m.getConversationId()
                    + " 不属于家庭 " + familyId);
        }
    }

    @Select("SELECT" + COLS + "FROM ask_message m"
          + " JOIN ask_conversation v ON v.id = m.conversation_id"
          + " WHERE v.family_id = #{familyId} AND m.conversation_id = #{cid} ORDER BY m.seq")
    List<AskMessage> byConversation(@Param("familyId") long familyId, @Param("cid") long conversationId);

    /** 下一个序号 —— 单家庭单机,COALESCE(MAX)+1 够用,不引锁 */
    @Select("SELECT COALESCE(MAX(m.seq), 0) + 1 FROM ask_message m"
          + " JOIN ask_conversation v ON v.id = m.conversation_id"
          + " WHERE v.family_id = #{familyId} AND m.conversation_id = #{cid}")
    int nextSeq(@Param("familyId") long familyId, @Param("cid") long conversationId);

    /** 送进模型上下文的历史:只取 user/assistant,旁白不算(它是给人看的) */
    @Select("SELECT" + COLS + "FROM ask_message m"
          + " JOIN ask_conversation v ON v.id = m.conversation_id"
          + " WHERE v.family_id = #{familyId} AND m.conversation_id = #{cid}"
          + " AND m.role IN ('user','assistant')"
          + " ORDER BY m.seq DESC LIMIT #{limit}")
    List<AskMessage> recentForContext(@Param("familyId") long familyId,
                                      @Param("cid") long conversationId, @Param("limit") int limit);

    @Update("UPDATE ask_message m JOIN ask_conversation v ON v.id = m.conversation_id"
          + " SET m.content_text = #{contentText}"
          + " WHERE v.family_id = #{familyId} AND m.id = #{id}")
    void updateContent(@Param("familyId") long familyId,
                       @Param("id") long id, @Param("contentText") String contentText);
}
