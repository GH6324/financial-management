package com.family.finance.domain.ask;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * v1.19 · 一把接入凭据。
 *
 * <p><b>明文永远不在这个对象里</b> —— 只有 {@code tokenHash}(argon2id)与
 * {@code tokenPrefix}(前缀明文,用于命中候选行与审计展示)。
 * 生成那一刻的明文由 {@code AccessTokenService.Issued} 单独返回,只出现一次。</p>
 *
 * <h3>换绑期间同一接入点有两行</h3>
 * <p>{@code accessPointId} 相同、{@code tokenPrefix} 不同。旧行的 {@code supersededBy}
 * 指向新行 —— 新密钥<b>首次被使用</b>时据此吊销旧行。
 * 用「新密钥真的被用了」这个事实收尾,而不是定时器猜。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AskAccessToken {
    private Long id;
    private Long familyId;
    /** 接入点 id;换绑期间同一 id 下有两行 */
    private Long accessPointId;
    private String name;
    /** argon2id(明文) */
    private String tokenHash;
    /** fmk_ + 8 位;唯一索引,校验时先按它命中候选行 */
    private String tokenPrefix;
    private String scope;
    private LocalDateTime expiresAt;
    /** 旧密钥指向新密钥(非空 = 本行是换绑中的旧密钥) */
    private Long supersededBy;
    private LocalDateTime revokedAt;
    private LocalDateTime lastUsedAt;
    /** 首次使用即通知,只通知一次 */
    private LocalDateTime firstUsedAt;
    private LocalDateTime createdAt;

    public AskScope scopeEnum() { return AskScope.parse(scope); }

    public boolean revoked() { return revokedAt != null; }

    public boolean expired(LocalDateTime now) {
        return expiresAt != null && expiresAt.isBefore(now);
    }

    /** 本行是换绑中的旧密钥 —— 仍然有效,但审计要标出来,页面要催用户换完 */
    public boolean rotating() { return supersededBy != null; }

    /** 距到期还有几天(负数 = 已过期) */
    public long daysToExpiry(LocalDateTime now) {
        return expiresAt == null ? Long.MAX_VALUE
                : java.time.Duration.between(now, expiresAt).toDays();
    }
}
