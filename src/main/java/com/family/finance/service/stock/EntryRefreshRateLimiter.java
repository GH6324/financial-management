package com.family.finance.service.stock;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v0.4.22 · /entry「拉取股价」按钮的限频器。
 *
 * <p>每个 family 60s 滑动窗口最多 3 次 · in-memory · 进程级别(单实例部署 OK)。
 * 跟 SMS 测试发送的「3 次/分」一致 · 防点点点滥用。</p>
 *
 * <p>实现:per family 一个 Deque · push 当前 timestamp · 头部 pop 掉超 60s 旧的 · 看 size。</p>
 */
@Component
public class EntryRefreshRateLimiter {

    /** 60s 滑动窗口 */
    static final long WINDOW_SECONDS = 60;
    /** 窗口内最多次数 */
    static final int MAX_PER_WINDOW = 3;

    private final ConcurrentHashMap<Long, Deque<Instant>> familyHistory = new ConcurrentHashMap<>();

    /**
     * 试图记录一次触发。
     *
     * @return true = 允许 + 已记录 · false = 超频拒绝
     */
    public synchronized boolean tryAcquire(long familyId) {
        Instant now = Instant.now();
        Instant cutoff = now.minusSeconds(WINDOW_SECONDS);
        Deque<Instant> hist = familyHistory.computeIfAbsent(familyId, k -> new ArrayDeque<>());
        // 清理过期
        while (!hist.isEmpty() && hist.peekFirst().isBefore(cutoff)) {
            hist.pollFirst();
        }
        if (hist.size() >= MAX_PER_WINDOW) {
            return false;
        }
        hist.addLast(now);
        return true;
    }

    /**
     * 当前窗口内距下次允许还需多少秒(粗粒度 · 用于 UI 文案)。
     * 返回 0 表示当前允许。
     */
    public synchronized long secondsUntilNextAllowed(long familyId) {
        Deque<Instant> hist = familyHistory.get(familyId);
        if (hist == null || hist.size() < MAX_PER_WINDOW) return 0;
        Instant earliest = hist.peekFirst();
        if (earliest == null) return 0;
        long remain = WINDOW_SECONDS - (Instant.now().getEpochSecond() - earliest.getEpochSecond());
        return Math.max(0, remain);
    }
}
