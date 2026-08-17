package com.family.finance.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.stereotype.Component;

/**
 * v1.15 · 把一个成员**已经开着的**登录状态就地作废。
 *
 * <p>两个调用场景:归档(FR-381「当场生效」)和改登录名(FR-380)。两者都不能只改数据库就完事 ——
 * 数据库改了,对方浏览器里那个 session 和那张 remember-me 票根还是好的。
 *
 * <p>「登录状态」实际由两样东西撑着,少收一样都不算踢掉:
 * <ol>
 *   <li><b>HTTP session</b> —— 走 {@link SessionRegistry},逐个 {@code expireNow()};</li>
 *   <li><b>remember-me 票根</b> —— {@code persistent_logins} 表,session 掉了它还能自动把人登回来。</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionKiller {

    private final SessionRegistry sessionRegistry;
    private final PersistentTokenRepository tokenRepository;

    /**
     * 作废该成员所有在线会话。
     *
     * <p>注意这里**不是** {@code getAllSessions(new MemberPrincipal(m), false)} ——
     * {@link MemberPrincipal} 没有 {@code equals}/{@code hashCode},新造一个实例去查
     * registry 的 map 永远查不到(默认走对象同一性),那样写会静默地什么都不踢。
     * 所以从 {@code getAllPrincipals()} 里拿 registry 自己持有的那个实例、按 memberId 认人。
     *
     * @return 实际作废的会话数(便于审计与测试断言)
     */
    public int killAllSessions(long memberId) {
        int killed = 0;
        for (Object p : sessionRegistry.getAllPrincipals()) {
            if (p instanceof MemberPrincipal mp && mp.getMemberId() == memberId) {
                for (SessionInformation si : sessionRegistry.getAllSessions(p, false)) {
                    si.expireNow();
                    killed++;
                }
            }
        }
        if (killed > 0) {
            log.info("[SessionKiller] member={} 作废在线会话 {} 个", memberId, killed);
        }
        return killed;
    }

    /**
     * 清掉该登录名下的 remember-me 票根。
     *
     * <p><b>改登录名时必须在 UPDATE 之前调用</b>:{@code persistent_logins} 是按 username 记账的,
     * 名字一改,旧行就变成谁也删不掉的孤儿 —— 而那张票根照样能把人自动登回来。
     */
    public void killRememberMe(String username) {
        tokenRepository.removeUserTokens(username);
    }
}
