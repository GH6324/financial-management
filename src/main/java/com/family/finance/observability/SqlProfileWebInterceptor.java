package com.family.finance.observability;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.service.config.FamilyConfigService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.util.List;

/**
 * v1.12 FR-351 · 请求边界 · 开关判定 / 收尾输出 / 清理 ThreadLocal。
 *
 * <h3>为什么是 HandlerInterceptor,不是 tech-design §5 写的 OncePerRequestFilter</h3>
 * 设计里写的是 filter,施工时改成了 interceptor,原因是<b>响应头写不进去</b>:
 * filter 在 {@code chain.doFilter()} <b>之后</b>才有机会设 {@code X-Sql-Count},
 * 而那时候页面已经流式输出、响应早就 committed(本仓库的 Thymeleaf 大页面都是 chunked,
 * 同一个坑在 v0.2 让 {@code /error} 失效过)—— 设了也不会发出去,静默丢。
 * {@code HandlerInterceptor} 有三个钩子刚好对上这三件事:
 * <ul>
 *   <li>{@code preHandle}:读开关 → 开始统计。</li>
 *   <li>{@code postHandle}:控制器已返回、<b>视图还没渲染</b>(响应还没 commit)→ 这是能写头的最后时机。</li>
 *   <li>{@code afterCompletion}:渲染也结束了 → 输出完整清单 + {@code finally} 清 ThreadLocal。</li>
 * </ul>
 *
 * <h3>头和日志的数字可能不同,这是有意的</h3>
 * {@code X-Sql-Count} 只能统计到<b>控制器阶段</b>(写头的时机决定的);日志里的清单是<b>全量</b>,
 * 含视图渲染期发的 SQL。两者不一致 = 模板渲染时还在查库 —— 那本身就是 FR-352 要找的东西,
 * 所以这个差值有诊断价值,不去把它抹平。
 *
 * <h3>开关查询不自计</h3>
 * 读 {@code family_runtime_config} 本身要发 SQL(命中 5s 缓存时不发)。所以
 * {@code preHandle} 里<b>先读开关、后 {@code start()}</b> —— 顺序反了会让清单里凭空多一条
 * {@code FamilyRuntimeConfigMapper.findValue},每次请求都在,像个假的 N+1。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SqlProfileWebInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Sql-Count";

    private final FamilyConfigService configService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 上一个请求若因异常没走到 afterCompletion(理论上不会),这里兜一层,避免串账
        SqlProfileContext.clear();
        Long familyId = currentFamilyId();
        if (familyId == null) return true;            // 未登录请求不统计(也拿不到 family 级开关)
        // 先读开关,再 start() —— 顺序不能反,否则开关查询自己会被计进去
        if (!configService.getBoolean(familyId, FamilyConfigService.K_SQL_PROFILER, false)) return true;
        SqlProfileContext.start();
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response,
                          Object handler, ModelAndView modelAndView) {
        if (!SqlProfileContext.active()) return;
        if (response.isCommitted()) return;           // 已提交(如 HTMX 片段流式写出)→ 设了也发不出去
        response.setHeader(HEADER, String.valueOf(SqlProfileContext.totalCount()));
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                               Object handler, Exception ex) {
        if (!SqlProfileContext.active()) return;
        try {
            List<SqlProfileContext.Stat> stats = SqlProfileContext.snapshotSorted();
            int total = 0;
            long nanos = 0;
            StringBuilder sb = new StringBuilder();
            for (SqlProfileContext.Stat s : stats) {
                total += s.count();
                nanos += s.totalNanos();
                sb.append("\n    ").append(s.count() >= 10 ? "!! " : "   ")
                        .append(String.format("%5d 次 %7d ms  %s", s.count(), s.totalMillis(), shortId(s.statementId())));
            }
            log.info("sql-profile · {} {}{} · SQL {} 条 / {} 个方法 · DB 累计 {} ms{}",
                    request.getMethod(), request.getRequestURI(),
                    request.getQueryString() == null ? "" : "?" + request.getQueryString(),
                    total, stats.size(), nanos / 1_000_000L, sb);
        } finally {
            SqlProfileContext.clear();               // 必须 finally:漏一次 → 线程复用后计数越滚越大
        }
    }

    /** {@code com.family.finance.repository.FactMapper.queryBase} → {@code FactMapper.queryBase} */
    private static String shortId(String id) {
        int i = id.lastIndexOf('.');
        if (i <= 0) return id;
        int j = id.lastIndexOf('.', i - 1);
        return j < 0 ? id : id.substring(j + 1);
    }

    private static Long currentFamilyId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof MemberPrincipal mp)) return null;
        return mp.getFamilyId();
    }
}
