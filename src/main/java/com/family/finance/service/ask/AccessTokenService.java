package com.family.finance.service.ask;

import com.family.finance.domain.ask.AskAccessToken;
import com.family.finance.domain.ask.AskAuditResult;
import com.family.finance.domain.ask.AskScope;
import com.family.finance.repository.AskAccessTokenMapper;
import com.family.finance.repository.AskAuditMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * v1.19 · 接入凭据:生成 / 校验 / 续期 / 换绑 / 吊销。
 *
 * <h3>为什么用 SHA-256 而不是 BCrypt / Argon2</h3>
 * <p>慢哈希(bcrypt/argon2)的意义是<b>拖慢对低熵口令的暴力破解</b>。
 * 而这里的凭据是 <b>32 字节 CSPRNG 随机数(256 bit 熵)</b> —— 拖库者拿到 hash 之后
 * 要还原它得穷举 2^256,慢哈希一点忙也帮不上,却带来两个实实在在的坏处:</p>
 * <ul>
 *   <li><b>每次调用多花几百毫秒</b> —— 而这是个会被公网扫的端点,等于自带 DoS 放大器</li>
 *   <li>hash 不能直接做唯一索引查找,得先按前缀捞候选行再逐个验</li>
 * </ul>
 * <p>所以这里用 <b>SHA-256 + 哈希列唯一索引</b>:一次等值查找命中,常数时间,
 * 且对 256 bit 随机串而言安全性没有任何损失。这与业界给 API token 的做法一致
 * (口令用慢哈希,高熵 token 用快哈希)。</p>
 *
 * <h3>换绑为什么不用定时器</h3>
 * <p>凭据存在<b>百炼那一侧</b>的 MCP 服务配置里,而百炼「部署后改配置必须先停止部署
 * → 修改 → 重新部署」。所以换绑期间旧密钥<b>必须保持有效</b>,否则每次轮换都断一次服务 ——
 * 那会让人干脆不轮换,反而更不安全。</p>
 * <p>收尾条件是<b>「新密钥第一次被真的用了」</b>这个事实,不是「过了 24 小时」这个猜测。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccessTokenService {

    /** 前缀有两个实际用途:① 被贴进公开仓库时 secret scanning 能按它告警 ② 审计页定位是哪一把 */
    public static final String PREFIX = "fmk_";
    private static final int RANDOM_BYTES = 32;
    private static final int PREFIX_KEEP = 8;
    /** 默认有效期;续期不换密钥,所以这个数字不必给得很长 */
    public static final int DEFAULT_DAYS = 90;
    /** 到期前多少天开始提醒 */
    public static final int WARN_DAYS = 7;

    private final AskAccessTokenMapper tokenMapper;
    private final AskAuditMapper auditMapper;
    private final SecureRandom random = new SecureRandom();

    /** 新发一把凭据的结果 —— <b>明文只在这里出现一次</b>,不入库、不落日志 */
    public record Issued(AskAccessToken token, String plaintext) {}

    /** 校验结果:判定 + 命中的凭据(未通过时为 null) */
    public record Verdict(AskAuditResult result, AskAccessToken token) {
        public boolean ok() { return result.passed(); }
    }

    // ──────────────────────────── 生成 ────────────────────────────

    /**
     * 新建一个接入点并发第一把凭据。
     *
     * @param days 有效期天数;`<= 0` 用默认
     */
    public Issued create(long familyId, String name, AskScope scope, int days) {
        long pointId = tokenMapper.maxAccessPointId(familyId) + 1;
        return issue(familyId, pointId, name, scope, days);
    }

    /**
     * 换绑:在**同一接入点**下发一把新凭据,<b>旧的保持有效</b>。
     *
     * <p>旧行标记 {@code superseded_by = 新行 id};新密钥首次被用时自动吊销旧行(见 {@link #verify}）。</p>
     */
    public Issued rotate(long familyId, long accessPointId) {
        List<AskAccessToken> live = tokenMapper.findByAccessPoint(accessPointId).stream()
                .filter(t -> t.getRevokedAt() == null)
                .toList();
        if (live.isEmpty()) throw new IllegalStateException("这个接入点已经没有可用凭据了,请重新创建");
        // 同一接入点最多两把并存 —— 不允许无限堆积
        if (live.size() >= 2) {
            throw new IllegalStateException("这个接入点已经在换绑中了。先去百炼把配置换成新口令,或者点「取消换绑」");
        }
        AskAccessToken old = live.get(0);
        Issued fresh = issue(familyId, accessPointId, old.getName(), old.scopeEnum(),
                (int) Math.max(1, old.daysToExpiry(LocalDateTime.now())));
        tokenMapper.markSuperseded(old.getId(), fresh.token().getId());
        log.info("ask access rotate · point={} old={} new={}",
                accessPointId, old.getTokenPrefix(), fresh.token().getTokenPrefix());
        return fresh;
    }

    private Issued issue(long familyId, long pointId, String name, AskScope scope, int days) {
        byte[] raw = new byte[RANDOM_BYTES];
        random.nextBytes(raw);
        String plaintext = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        AskAccessToken t = AskAccessToken.builder()
                .familyId(familyId)
                .accessPointId(pointId)
                .name(name == null || name.isBlank() ? "未命名接入点" : name.trim())
                .tokenHash(sha256(plaintext))
                .tokenPrefix(plaintext.substring(0, PREFIX.length() + PREFIX_KEEP))
                .scope((scope == null ? AskScope.AGGREGATE : scope).getCode())
                .expiresAt(LocalDateTime.now().plusDays(days > 0 ? days : DEFAULT_DAYS))
                .build();
        tokenMapper.insert(t);
        return new Issued(t, plaintext);
    }

    // ──────────────────────────── 校验 ────────────────────────────

    /**
     * 校验一把凭据。
     *
     * <p><b>调用方必须把「未通过」一律渲染成 404</b>(不是 401)——
     * 401 等于告诉扫描者「这里有东西,只是你没凭据」,而这背后是一个家庭的全部资产。
     * 区分只保留在审计里,给用户看。</p>
     */
    public Verdict verify(String bearer, AskScope required) {
        if (bearer == null || bearer.isBlank()) return noMatch();
        String plain = bearer.startsWith("Bearer ") ? bearer.substring(7).trim() : bearer.trim();
        if (!plain.startsWith(PREFIX)) return noMatch();

        Optional<AskAccessToken> hit = tokenMapper.findByHash(sha256(plain));
        if (hit.isEmpty()) return noMatch();
        AskAccessToken t = hit.get();

        if (t.revoked()) return new Verdict(AskAuditResult.REVOKED, null);
        if (t.expired(LocalDateTime.now())) return new Verdict(AskAuditResult.EXPIRED, null);
        if (!t.scopeEnum().covers(required == null ? AskScope.AGGREGATE : required)) {
            return new Verdict(AskAuditResult.SCOPE, t);
        }

        // 换绑收尾:新密钥第一次被真的用了 → 吊销它顶替的那一把
        AskAuditResult r = AskAuditResult.OK;
        if (t.rotating()) {
            r = AskAuditResult.OK_OLD;                 // 还在用旧的 —— 页面要催
        } else if (supersedes(t)) {
            r = AskAuditResult.OK_NEW;
            closeRotation(t);
        }
        return new Verdict(r, t);
    }

    /**
     * 谁都没匹配上 —— 这时候才分「功能没开」还是「有人拿错口令在探」。
     *
     * <p><b>次序很要紧</b>:先看有没有命中具体某一把,命中了就用它的判定
     * (过期就是 EXPIRED,吊销就是 REVOKED)。反过来先判 OFF 的话,
     * 「用户唯一那把口令过期了」会被报成「功能没开」—— 管理页于是没法提示他去续期,
     * 而那正是最该被提示的场景。</p>
     *
     * <p>对外两者都是 404,一模一样,不透露差别;区分只留在审计里:
     * 一串 INVALID 是有人在探,一串 OFF 只是功能没开着。混在一起的话,
     * 「被扫了」会淹没在噪声里。</p>
     */
    private Verdict noMatch() {
        return new Verdict(tokenMapper.countUsableAll() == 0
                ? AskAuditResult.OFF : AskAuditResult.INVALID, null);
    }

    /** 本行是否顶替了别的行(即它是换绑出来的新密钥) */
    private boolean supersedes(AskAccessToken t) {
        return tokenMapper.findByAccessPoint(t.getAccessPointId()).stream()
                .anyMatch(o -> t.getId().equals(o.getSupersededBy()) && o.getRevokedAt() == null);
    }

    /** 用「新密钥被使用」这个事实收尾换绑,而不是定时器 */
    private void closeRotation(AskAccessToken fresh) {
        tokenMapper.findByAccessPoint(fresh.getAccessPointId()).stream()
                .filter(o -> fresh.getId().equals(o.getSupersededBy()) && o.getRevokedAt() == null)
                .forEach(o -> {
                    tokenMapper.revoke(o.getId());
                    log.info("ask access rotate 完成 · point={} 旧口令 {} 已失效",
                            fresh.getAccessPointId(), o.getTokenPrefix());
                });
    }

    // ──────────────────────── 续期 / 吊销 ────────────────────────

    /**
     * 续期 —— <b>只改到期时间,不换密钥</b>。
     *
     * <p>这是整套设计里最省事的一条:多数人点「重新生成」其实只是因为看到「即将过期」。
     * 把续期和换密钥拆开之后,那类需求<b>根本不用碰百炼</b>。</p>
     */
    public void renew(long tokenId, int days) {
        tokenMapper.renew(tokenId, LocalDateTime.now().plusDays(days > 0 ? days : DEFAULT_DAYS));
    }

    /** 紧急断开:该接入点<b>全部</b>密钥(含换绑中的新密钥)一起失效 */
    public int killAccessPoint(long accessPointId) {
        int n = tokenMapper.revokeAccessPoint(accessPointId);
        log.warn("ask access 紧急断开 · point={} 失效 {} 把", accessPointId, n);
        return n;
    }

    /** 关掉整个功能 */
    public int killAll(long familyId) {
        int n = tokenMapper.revokeAll(familyId);
        log.warn("ask access 全部断开 · family={} 失效 {} 把", familyId, n);
        return n;
    }

    /** 功能是否启用 —— 没有任何可用凭据 = 未启用 = 端点返回 404 */
    public boolean enabled(long familyId) {
        return tokenMapper.countUsable(familyId) > 0;
    }

    public List<AskAccessToken> list(long familyId) {
        return tokenMapper.findActiveByFamily(familyId);
    }

    // ──────────────────────────── 工具 ────────────────────────────

    /**
     * SHA-256 十六进制。
     *
     * <p>见类注释:输入是 256 bit 随机串,快哈希在这里既安全又必要。</p>
     */
    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
