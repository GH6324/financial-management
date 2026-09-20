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

    /**
     * <b>family_id 隔离的合法例外</b>(v1.24 普查登记)· 登录入口。
     *
     * <p>{@code familyId} 是这条查询的<b>产物</b>而不是输入 —— 用户在登录框里只打了用户名,
     * 这一刻还不知道他属于哪个家。加一个 familyId 参数只能从别处猜一个传进来,比不加更危险。
     * 归属在返回之后立刻确立({@code MemberPrincipal.familyId} 之后是一切查询的作用域)。
     * 例外清单由护栏 {@code v1240-FAMILY-ISOLATION} 钉死,新增例外必须同时改护栏。</p>
     */
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
             WHERE family_id = #{familyId}
               AND id = #{id}
            """)
    Optional<Member> findById(@Param("familyId") long familyId, @Param("id") long id);

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
     *
     * <p><b>family_id 隔离的合法例外</b>(v1.24 普查登记):用户名是<b>全局唯一命名空间</b>,
     * 占用检查按家庭拆开就等于允许两个家庭注册同一个登录名 —— 那之后
     * {@link #findByUsername} 会返回两行,登录直接坏掉。这条<b>必须</b>跨家庭。
     * 它不返回任何家庭数据,只返回一个计数。</p>
     */
    @Select("SELECT COUNT(*) FROM member WHERE username = #{username}")
    int existsUsername(@Param("username") String username);

    /** v1.15 FR-380 · 改登录名。调用前必须先清 persistent_logins(那张表按 username 记账)。 */
    @Update("UPDATE member SET username = #{username}"
          + " WHERE family_id = #{familyId} AND id = #{id}")
    int updateUsername(@Param("familyId") long familyId,
                       @Param("id") long id, @Param("username") String username);

    /** v1.15 FR-381 · 归档(幂等:已归档的不重置时间戳)。 */
    @Update("UPDATE member SET archived_at = NOW(3)"
          + " WHERE family_id = #{familyId} AND id = #{id} AND archived_at IS NULL")
    int archive(@Param("familyId") long familyId, @Param("id") long id);

    /** v1.15 FR-381 · 撤销归档。 */
    @Update("UPDATE member SET archived_at = NULL"
          + " WHERE family_id = #{familyId} AND id = #{id}")
    int restore(@Param("familyId") long familyId, @Param("id") long id);

    /** v1.15 FR-383 · 物理删除 —— 只在 {@code MemberReferenceScanner} 扫出零引用时才允许调用。 */
    @org.apache.ibatis.annotations.Delete("DELETE FROM member"
          + " WHERE family_id = #{familyId} AND id = #{id}")
    int deleteById(@Param("familyId") long familyId, @Param("id") long id);

    @Update("""
            UPDATE member
               SET password_hash = #{hash},
                   must_change_pw = #{mustChangePw}
             WHERE family_id = #{familyId}
               AND id = #{id}
            """)
    int updatePasswordHash(@Param("familyId") long familyId,
                           @Param("id") long id,
                           @Param("hash") String hash,
                           @Param("mustChangePw") boolean mustChangePw);

    /**
     * 首次部署引导用 · 找出密码仍为 V2__seed.sql 占位符的种子成员。
     * 仅 {@code ProdSeedRunner}(prod profile)启动时用,设过临时密码后即不再命中(幂等)。
     */
    /**
     * <p><b>family_id 隔离的合法例外</b>(v1.24 普查登记):首次部署引导跑在<b>任何家庭上下文之前</b>
     * —— 它的任务就是把库里所有还是占位符密码的种子账号找出来设临时密码,按家庭拆开就漏人。
     * 只在 prod profile 启动时跑一次,设过密码即不再命中。</p>
     */
    @Select("""
            SELECT id, family_id, username, password_hash, display_name, role_label,
                   phone, must_change_pw, archived_at, last_login_at, created_at, updated_at
              FROM member
             WHERE password_hash LIKE 'PLACEHOLDER%'
            """)
    List<Member> findSeedPlaceholders();

    @Update("UPDATE member SET last_login_at = NOW(3)"
          + " WHERE family_id = #{familyId} AND id = #{id}")
    int touchLastLogin(@Param("familyId") long familyId, @Param("id") long id);

    @Update("""
            UPDATE member
               SET display_name = #{displayName},
                   role_label = #{roleLabel}
             WHERE family_id = #{familyId}
               AND id = #{id}
            """)
    int updateProfile(@Param("familyId") long familyId,
                      @Param("id") long id,
                      @Param("displayName") String displayName,
                      @Param("roleLabel") String roleLabel);

    /**
     * v0.4.14 FR-63c · 单独更新成员手机号(私密 · 短信提醒用)。
     * 注意:phone 绝不进 PromptBuilder / 任何 LLM prompt / audit_log 明文。
     */
    @Update("UPDATE member SET phone = #{phone}"
          + " WHERE family_id = #{familyId} AND id = #{id}")
    int updatePhone(@Param("familyId") long familyId,
                    @Param("id") long id, @Param("phone") String phone);

    @org.apache.ibatis.annotations.Insert("""
            INSERT INTO member (family_id, username, password_hash, display_name, role_label, must_change_pw)
            VALUES (#{familyId}, #{username}, #{passwordHash}, #{displayName}, #{roleLabel}, 1)
            """)
    @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(com.family.finance.domain.member.Member member);
}
