package com.family.finance.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * v1.19 · 接口调用审计。
 *
 * <p><b>不记返回体、不记请求参数里的金额</b> —— 返回体就是家底数据,
 * 记进审计表只是多造一个泄漏面。这里只回答「谁、什么时候、调了哪个接口、结果如何」。</p>
 */
@Mapper
public interface AskAuditMapper {

    @Insert("""
            INSERT INTO ask_access_audit
                (family_id, token_prefix, tool_name, result, src_ip, user_agent, duration_ms)
            VALUES
                (#{familyId}, #{tokenPrefix}, #{toolName}, #{result}, #{srcIp}, #{userAgent}, #{durationMs})
            """)
    int insert(@Param("familyId") long familyId,
               @Param("tokenPrefix") String tokenPrefix,
               @Param("toolName") String toolName,
               @Param("result") String result,
               @Param("srcIp") String srcIp,
               @Param("userAgent") String userAgent,
               @Param("durationMs") Integer durationMs);

    /** 管理页「最近调用」 */
    record Row(String tokenPrefix, String toolName, String result,
               String srcIp, String userAgent, Integer durationMs, LocalDateTime createdAt) {}

    @Select("""
            SELECT token_prefix AS tokenPrefix, tool_name AS toolName, result,
                   src_ip AS srcIp, user_agent AS userAgent,
                   duration_ms AS durationMs, created_at AS createdAt
              FROM ask_access_audit
             WHERE family_id = #{familyId}
             ORDER BY created_at DESC
             LIMIT #{limit}
            """)
    List<Row> recent(@Param("familyId") long familyId, @Param("limit") int limit);

    /** 换绑进度:该接入点最近是否还在用旧密钥 */
    @Select("""
            SELECT COUNT(*) FROM ask_access_audit
             WHERE token_prefix = #{prefix} AND result = 'OK_NEW'
            """)
    int countNewKeyUsed(@Param("prefix") String prefix);
}
