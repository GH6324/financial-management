package com.family.finance.repository;

import com.family.finance.domain.ask.AskAccessToken;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * v1.19 · 接入凭据。
 *
 * <p><b>校验按 {@code token_hash} 唯一索引等值命中</b> —— 一次查找,常数时间。
 * 凭据是 256 bit 随机串,用快哈希(SHA-256)既安全又避免把公网端点变成 DoS 放大器;
 * 理由见 {@code AccessTokenService} 类注释。{@code token_prefix} 只用于审计展示与识别。</p>
 */
@Mapper
public interface AskAccessTokenMapper {

    /**
     * 列清单。
     *
     * <p>刻意用普通字符串而不是文本块:文本块会<b>吃掉行尾空格</b>,
     * 于是 {@code """...SELECT """ + COLS} 会拼成 {@code SELECTid}(真在 beta 上炸过)。
     * 前后各留一个空格,拼在哪儿都不会粘住。</p>
     */
    String COLS = " id, family_id AS familyId, access_point_id AS accessPointId, name,"
                + " token_hash AS tokenHash, token_prefix AS tokenPrefix, scope,"
                + " expires_at AS expiresAt, superseded_by AS supersededBy,"
                + " revoked_at AS revokedAt, last_used_at AS lastUsedAt,"
                + " first_used_at AS firstUsedAt, created_at AS createdAt ";

    @Insert("""
            INSERT INTO ask_access_token
                (family_id, access_point_id, name, token_hash, token_prefix, scope, expires_at)
            VALUES
                (#{familyId}, #{accessPointId}, #{name}, #{tokenHash}, #{tokenPrefix}, #{scope}, #{expiresAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AskAccessToken t);

    /**
     * 校验入口:按 <b>hash 唯一索引</b>等值命中,O(1)。
     *
     * <p>不按前缀捞候选再逐个验 —— 那是慢哈希(bcrypt/argon2)才需要的绕法。
     * 这里凭据是 256 bit 随机串,用 SHA-256 即可,于是能直接对 hash 建唯一索引。
     * 见 {@code AccessTokenService} 的类注释。</p>
     */
    @Select("SELECT " + COLS + " FROM ask_access_token WHERE token_hash = #{hash}")
    Optional<AskAccessToken> findByHash(@Param("hash") String hash);

    /** 审计展示用:按前缀找(不用于校验) */
    @Select("SELECT " + COLS + " FROM ask_access_token WHERE token_prefix = #{prefix}")
    Optional<AskAccessToken> findByPrefix(@Param("prefix") String prefix);

    @Select("SELECT " + COLS + " FROM ask_access_token WHERE id = #{id}")
    Optional<AskAccessToken> findById(@Param("id") long id);

    /** 管理页列表:未吊销的,按接入点分组、新的在前 */
    @Select("SELECT " + COLS + " FROM ask_access_token"
          + " WHERE family_id = #{familyId} AND revoked_at IS NULL"
          + " ORDER BY access_point_id DESC, created_at DESC")
    List<AskAccessToken> findActiveByFamily(@Param("familyId") long familyId);

    /** 同一接入点下的全部(含已吊销)—— 紧急断开时要连换绑中的新密钥一起干掉 */
    @Select("SELECT " + COLS + " FROM ask_access_token WHERE access_point_id = #{pointId}")
    List<AskAccessToken> findByAccessPoint(@Param("pointId") long pointId);

    /** 家庭里是否还有任何可用凭据 —— 决定 /mcp 是 404 还是继续走鉴权 */
    @Select("""
            SELECT COUNT(*) FROM ask_access_token
             WHERE family_id = #{familyId} AND revoked_at IS NULL AND expires_at > NOW(3)
            """)
    int countUsable(@Param("familyId") long familyId);

    @Select("SELECT COALESCE(MAX(access_point_id), 0) FROM ask_access_token WHERE family_id = #{familyId}")
    long maxAccessPointId(@Param("familyId") long familyId);

    /** 续期:**只改 expires_at,不动 token_hash** —— 改了就意味着用户又得去百炼一趟 */
    @Update("UPDATE ask_access_token SET expires_at = #{expiresAt} WHERE id = #{id}")
    int renew(@Param("id") long id, @Param("expiresAt") java.time.LocalDateTime expiresAt);

    /** 换绑:旧行指向新行 */
    @Update("UPDATE ask_access_token SET superseded_by = #{newId} WHERE id = #{oldId}")
    int markSuperseded(@Param("oldId") long oldId, @Param("newId") long newId);

    @Update("UPDATE ask_access_token SET revoked_at = NOW(3) WHERE id = #{id} AND revoked_at IS NULL")
    int revoke(@Param("id") long id);

    /** 紧急断开:该接入点全部密钥(含换绑中的新密钥)一起失效 */
    @Update("""
            UPDATE ask_access_token SET revoked_at = NOW(3)
             WHERE access_point_id = #{pointId} AND revoked_at IS NULL
            """)
    int revokeAccessPoint(@Param("pointId") long pointId);

    /** 关掉整个功能 */
    @Update("UPDATE ask_access_token SET revoked_at = NOW(3) WHERE family_id = #{familyId} AND revoked_at IS NULL")
    int revokeAll(@Param("familyId") long familyId);

    /** 异步节流更新;首次使用时顺带写 first_used_at(只写一次) */
    @Update("""
            UPDATE ask_access_token
               SET last_used_at  = NOW(3),
                   first_used_at = COALESCE(first_used_at, NOW(3))
             WHERE id = #{id}
            """)
    int touch(@Param("id") long id);

    /**
     * 全库还有几把可用凭据。
     *
     * <p>{@code verify} 拿不到 familyId(凭据还没解析出来),所以这里不带家庭维度。
     * 本项目是单家庭部署(prd §22.3 类 A),两者等价;真要多家庭了,
     * 这个方法会是必须改的那一个 —— 留在这里比藏在 verify 里好找。</p>
     */
    @Select("SELECT COUNT(*) FROM ask_access_token WHERE revoked_at IS NULL AND expires_at > NOW()")
    int countUsableAll();
}
