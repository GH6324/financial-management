package com.family.finance.service.ask;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v1.19 · 进程内限流 + 认证失败封禁。
 *
 * <p><b>为什么不用 Redis</b>:本项目是单实例自托管,没有 Redis 也不打算引入。
 * 一个 {@code ConcurrentHashMap} + 原子计数就够,且没有网络往返 ——
 * 而这是个会被公网扫的端点,限流本身不该成为新的延迟来源。</p>
 *
 * <p><b>为什么要有失败封禁</b>:端点暴露到公网就一定会被扫。
 * 只做限流不做封禁,扫描者可以用远低于限流阈值的速率慢慢试 ——
 * 虽然 256 bit 随机串试不出来,但日志会被刷满,而且我们会失去「异常」这个信号。</p>
 */
@Slf4j
@Component
public class AskRateLimiter {

    /** 每分钟 · 每凭据 */
    public static final int PER_MINUTE = 60;
    /** 每天 · 每凭据 */
    public static final int PER_DAY = 2000;
    /** 同一来源连续认证失败多少次触发封禁 */
    public static final int FAIL_BAN_THRESHOLD = 10;
    /** 封禁时长 */
    public static final Duration BAN_FOR = Duration.ofMinutes(30);

    private record Window(Instant start, AtomicInteger count) {}

    private final Map<String, Window> minute = new ConcurrentHashMap<>();
    private final Map<String, Window> day = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();
    private final Map<String, Instant> banned = new ConcurrentHashMap<>();

    /** 限流:返回 true = 放行 */
    public boolean allow(String key) {
        return hit(minute, key, Duration.ofMinutes(1), PER_MINUTE)
                && hit(day, key, Duration.ofDays(1), PER_DAY);
    }

    private boolean hit(Map<String, Window> store, String key, Duration span, int limit) {
        Instant now = Instant.now();
        Window w = store.compute(key, (k, cur) ->
                (cur == null || Duration.between(cur.start(), now).compareTo(span) >= 0)
                        ? new Window(now, new AtomicInteger(0)) : cur);
        return w.count().incrementAndGet() <= limit;
    }

    /** 来源是否处于封禁中 */
    public boolean isBanned(String src) {
        if (src == null) return false;
        Instant until = banned.get(src);
        if (until == null) return false;
        if (Instant.now().isAfter(until)) { banned.remove(src); failures.remove(src); return false; }
        return true;
    }

    /** 记一次认证失败;达到阈值即封禁并返回 true */
    public boolean recordFailure(String src) {
        if (src == null) return false;
        int n = failures.computeIfAbsent(src, k -> new AtomicInteger()).incrementAndGet();
        if (n >= FAIL_BAN_THRESHOLD) {
            banned.put(src, Instant.now().plus(BAN_FOR));
            failures.remove(src);
            log.warn("ask access · 来源 {} 连续认证失败 {} 次,封禁 {} 分钟", src, n, BAN_FOR.toMinutes());
            return true;
        }
        return false;
    }

    /** 认证成功即清零 —— 偶发的输错不该累积成封禁 */
    public void recordSuccess(String src) {
        if (src != null) failures.remove(src);
    }

    /** 仅供测试:清空全部状态 */
    public void resetAll() {
        minute.clear(); day.clear(); failures.clear(); banned.clear();
    }
}
