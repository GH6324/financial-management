package com.family.finance.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * v1.19 · agent 够不着时的反馈。
 *
 * <p>不是错误上报,是<b>产品输入</b> —— 用户真的问了什么、而我们没有对应能力,
 * 比坐着猜准得多。管理页把它列出来,作为下一版加接口的依据。</p>
 */
@Mapper
public interface AskUnmetNeedMapper {

    @Insert("""
            INSERT INTO ask_unmet_need (family_id, question, needed)
            VALUES (#{familyId}, #{question}, #{needed})
            """)
    int insert(@Param("familyId") long familyId,
               @Param("question") String question,
               @Param("needed") String needed);

    record Row(String question, String needed, LocalDateTime createdAt) {}

    @Select("""
            SELECT question, needed, created_at AS createdAt
              FROM ask_unmet_need
             WHERE family_id = #{familyId}
             ORDER BY created_at DESC
             LIMIT #{limit}
            """)
    List<Row> recent(@Param("familyId") long familyId, @Param("limit") int limit);
}
