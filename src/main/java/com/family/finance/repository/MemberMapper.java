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

    /**
     * v1.15 FR-382 · 全体成员(含已归档)· 按 id 序。
     * <p>历史数据里的名字必须查得到 —— 归档一个人不该让他三年前记的账变成「成员#7」。
     * 唯一合法调用方是 {@code MemberDirectory};别处要名字映射一律走它,
     * 护栏 {@code v115-MEMBER-NAME-MAP-INCLUDES-ARCHIVED} 钉这条。
     */
    @Select("""
            SELECT id, family_id, username, password_hash, display_name, role_label,
                   phone, must_change_pw, archived_at, last_login_at, created_at, updated_at
              FROM member
             WHERE family_id = #{familyId}
             ORDER BY id
            """)
    List<Member> findAllByFamily(@Param("familyId") long familyId);

    /**
     * v1.15 FR-380 · 登录名占用检查 —— 查的是**全表**,不限家庭、不排除已归档。
     * username 是登录凭据的主键面,归档的人也还占着他的名字。
     */
    @Select("SELECT COUNT(*) FROM member WHERE username = #{username}")
    int existsUsername(@Param("username") String username);

    /** v1.15 FR-380 · 改登录名。调用前必须先清 persistent_logins(那张表按 username 记账)。 */
    @Update("UPDATE member SET username = #{username} WHERE id = #{id}")
    int updateUsername(@Param("id") long id, @Param("username") String username);

    /** v1.15 FR-381 · 归档(幂等:已归档的不重置时间戳)。 */
    @Update("UPDATE member SET archived_at = NOW(3) WHERE id = #{id} AND archived_at IS NULL")
    int archive(@Param("id") long id);

    /** v1.15 FR-381 · 撤销归档。 */
    @Update("UPDATE member SET archived_at = NULL WHERE id = #{id}")
    int restore(@Param("id") long id);

    /** v1.15 FR-383 · 物理删除 —— 只在 {@code MemberReferenceScanner} 扫出零引用时才允许调用。 */
    @org.apache.ibatis.annotations.Delete("DELETE FROM member WHERE id = #{id}")
    int deleteById(@Param("id") long id);

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
