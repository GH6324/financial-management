package com.family.finance.repository;

import com.family.finance.domain.member.Member;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * Member MyBatis Mapper · 简单 CRUD 直接用注解,复杂查询走 mapper/*.xml。
 */
@Mapper
public interface MemberMapper {

    @Select("""
            SELECT id, family_id, username, password_hash, display_name, role_label,
                   phone, must_change_pw, archived_at, last_login_at, created_at, updated_at
              FROM member
             WHERE username = #{username}
            """)
    Optional<Member> findByUsername(@Param("username") String username);

    @Select("""
            SELECT id, family_id, username, password_hash, display_name, role_label,
                   phone, must_change_pw, archived_at, last_login_at, created_at, updated_at
              FROM member
             WHERE id = #{id}
            """)
    Optional<Member> findById(@Param("id") long id);

    @Select("""
            SELECT id, family_id, username, password_hash, display_name, role_label,
                   phone, must_change_pw, archived_at, last_login_at, created_at, updated_at
              FROM member
             WHERE family_id = #{familyId}
               AND archived_at IS NULL
             ORDER BY id
            """)
    List<Member> findActiveByFamily(@Param("familyId") long familyId);

    @Select("""
            SELECT COUNT(*)
              FROM member
             WHERE family_id = #{familyId}
               AND archived_at IS NULL
            """)
    int countActiveByFamily(@Param("familyId") long familyId);

    @Update("""
            UPDATE member
               SET password_hash = #{hash},
                   must_change_pw = #{mustChangePw}
             WHERE id = #{id}
            """)
    int updatePasswordHash(@Param("id") long id,
                           @Param("hash") String hash,
                           @Param("mustChangePw") boolean mustChangePw);

    /**
     * 首次部署引导用 · 找出密码仍为 V2__seed.sql 占位符的种子成员。
     * 仅 {@code ProdSeedRunner}(prod profile)启动时用,设过临时密码后即不再命中(幂等)。
     */
    @Select("""
            SELECT id, family_id, username, password_hash, display_name, role_label,
                   phone, must_change_pw, archived_at, last_login_at, created_at, updated_at
              FROM member
             WHERE password_hash LIKE 'PLACEHOLDER%'
            """)
    List<Member> findSeedPlaceholders();

    @Update("UPDATE member SET last_login_at = NOW(3) WHERE id = #{id}")
    int touchLastLogin(@Param("id") long id);

    @Update("""
            UPDATE member
               SET display_name = #{displayName},
                   role_label = #{roleLabel}
             WHERE id = #{id}
            """)
    int updateProfile(@Param("id") long id,
                      @Param("displayName") String displayName,
                      @Param("roleLabel") String roleLabel);

    /**
     * v0.4.14 FR-63c · 单独更新成员手机号(私密 · 短信提醒用)。
     * 注意:phone 绝不进 PromptBuilder / 任何 LLM prompt / audit_log 明文。
     */
    @Update("UPDATE member SET phone = #{phone} WHERE id = #{id}")
    int updatePhone(@Param("id") long id, @Param("phone") String phone);

    @org.apache.ibatis.annotations.Insert("""
            INSERT INTO member (family_id, username, password_hash, display_name, role_label, must_change_pw)
            VALUES (#{familyId}, #{username}, #{passwordHash}, #{displayName}, #{roleLabel}, 1)
            """)
    @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(com.family.finance.domain.member.Member member);
}
