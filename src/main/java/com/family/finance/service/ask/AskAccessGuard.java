package com.family.finance.service.ask;

import com.family.finance.domain.ask.AskAccessToken;
import com.family.finance.domain.ask.AskAuditResult;
import com.family.finance.domain.ask.AskScope;
import com.family.finance.repository.AskAccessTokenMapper;
import com.family.finance.repository.AskAuditMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v1.19 · 对外入口的唯一守卫。
 *
 * <h3>为什么未通过一律 404 而不是 401</h3>
 * <p>{@code 401} 等于告诉扫描者「这里有东西,只是你没凭据」—— 而这个端点背后是一个家庭的
 * <b>全部资产数据</b>。而且百炼的 MCP 客户端<b>本来就不走</b> {@code 401 + WWW-Authenticate}
 * 的发现流程,所以返 404 我们<b>不损失任何东西</b>。</p>
 *
 * <p>区分只保留在<b>审计</b>里(INVALID / EXPIRED / REVOKED / SCOPE / RATE)——
 * 用户要能看懂是「过期了」还是「填错了」,否则他只看到一片红,不知道该续期还是该重填。</p>
 *
 * <h3>为什么要有失败封禁</h3>
 * <p>暴露到公网就一定会被扫。只限流不封禁的话,扫描者能用远低于阈值的速率慢慢试:
 * 256 bit 随机串确实试不出来,但日志会被刷满,而且我们会<b>失去「异常」这个信号</b>——
 * 而这个信号是发现凭据泄露的唯一手段。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AskAccessGuard {

    /** last_used_at 的写库节流:不必每次调用都写 */
    private static final Duration TOUCH_THROTTLE = Duration.ofSeconds(60);

    private final AccessTokenService tokenService;
    private final AskRateLimiter limiter;
    private final AskAccessTokenMapper tokenMapper;
    private final AskAuditMapper auditMapper;
    private final com.family.finance.service.AuditLogService auditLogService;

    private final Map<Long, Instant> lastTouch = new ConcurrentHashMap<>();

    /** 守卫结论:通过时带 familyId 与实际 scope */
    public record Pass(boolean ok, AskAuditResult result, Long familyId, AskScope scope, Long tokenId) {
        public static Pass deny(AskAuditResult r) { return new Pass(false, r, null, null, null); }
    }

    /**
     * 校验一次外部调用。
     *
     * <p><b>调用方拿到 {@code !ok} 时必须渲染 404</b>(除 SCOPE 用 403、RATE 用 429)。</p>
     */
    public Pass check(HttpServletRequest req, AskScope required, String toolName) {
        String src = clientIp(req);
        String ua = header(req, "User-Agent", 128);

        // ① 被封禁的来源:直接挡,不再消耗一次哈希计算
        if (limiter.isBanned(src)) {
            record(null, "-", toolName, AskAuditResult.INVALID, src, ua, null);
            return Pass.deny(AskAuditResult.INVALID);
        }

        String bearer = req.getHeader("Authorization");
        AccessTokenService.Verdict v = tokenService.verify(bearer, required);

        if (!v.ok()) {
            String prefix = v.token() == null ? "-" : v.token().getTokenPrefix();
            Long fam = v.token() == null ? null : v.token().getFamilyId();
            record(fam, prefix, toolName, v.result(), src, ua, null);
            // scope 不足不算「认证失败」—— 凭据是真的,只是权限不够,不该因此封禁
            if (v.result() != AskAuditResult.SCOPE && limiter.recordFailure(src)) {
                notifyBanned(src);
            }
            return Pass.deny(v.result());
        }

        AskAccessToken t = v.token();
        limiter.recordSuccess(src);

        // ② 限流按凭据算(不按 IP)—— 百炼没有固定出口 IP,按 IP 限流会误伤
        if (!limiter.allow("tok:" + t.getId())) {
            record(t.getFamilyId(), t.getTokenPrefix(), toolName, AskAuditResult.RATE, src, ua, null);
            return Pass.deny(AskAuditResult.RATE);
        }

        boolean firstUse = t.getFirstUsedAt() == null;
        touchThrottled(t.getId());
        record(t.getFamilyId(), t.getTokenPrefix(), toolName, v.result(), src, ua, null);

        // ③ 首次被使用即通知 —— 第一次异常使用就能被发现
        if (firstUse) notifyFirstUse(t, src, ua);
        // ④ 还在用旧口令 → 提醒去百炼换完(换绑没收尾)
        if (v.result() == AskAuditResult.OK_OLD) {
            log.info("ask access · 凭据 {} 仍在使用旧口令,换绑尚未完成", t.getTokenPrefix());
        }

        return new Pass(true, v.result(), t.getFamilyId(), t.scopeEnum(), t.getId());
    }

    /** 功能是否启用 —— 未启用时端点直接 404,连鉴权都不走 */
    public boolean enabled(long familyId) {
        return tokenService.enabled(familyId);
    }

    // ──────────────────────── 内部 ────────────────────────

    private void touchThrottled(long tokenId) {
        Instant now = Instant.now();
        Instant prev = lastTouch.get(tokenId);
        if (prev == null || Duration.between(prev, now).compareTo(TOUCH_THROTTLE) >= 0) {
            lastTouch.put(tokenId, now);
            try {
                tokenMapper.touch(tokenId);
            } catch (Exception e) {
                log.debug("touch 失败(不影响调用):{}", e.toString());
            }
        }
    }

    /** 审计永不阻塞调用;写失败只记一行 debug */
    private void record(Long familyId, String prefix, String tool, AskAuditResult r,
                        String src, String ua, Integer ms) {
        try {
            auditMapper.insert(familyId == null ? 0L : familyId, prefix, tool, r.name(), src, ua, ms);
        } catch (Exception e) {
            log.debug("ask 审计写入失败:{}", e.toString());
        }
    }

    private void notifyFirstUse(AskAccessToken t, String src, String ua) {
        try {
            auditLogService.record(t.getFamilyId(), null,
                    com.family.finance.domain.audit.AuditLogType.SYSTEM, "ask_access", t.getId(),
                    "AI 接入凭据「" + t.getName() + "」(" + t.getTokenPrefix() + ")首次被使用 · 来源 " + src
                  + " · 如果这不是你配置的接入,请立刻去「AI 接入」点断开");
        } catch (Exception e) {
            log.debug("首次使用通知失败:{}", e.toString());
        }
    }

    private void notifyBanned(String src) {
        log.warn("ask access · 来源 {} 认证失败过多,已临时封禁", src);
    }

    /** 取真实来源 IP:走 nginx 反代时 remoteAddr 是 127.0.0.1 */
    static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return trim(comma > 0 ? xff.substring(0, comma) : xff, 64);
        }
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return trim(real, 64);
        return trim(req.getRemoteAddr(), 64);
    }

    private static String header(HttpServletRequest req, String name, int max) {
        return trim(req.getHeader(name), max);
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
