package com.family.finance.service.stock;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v0.4.22 · /entry「拉取股价」按钮限频器单测 · 锁住 3/60s 行为。
 */
class EntryRefreshRateLimiterTest {

    @Test
    void firstThreeCalls_allAllowed() {
        EntryRefreshRateLimiter rl = new EntryRefreshRateLimiter();
        assertThat(rl.tryAcquire(1L)).isTrue();
        assertThat(rl.tryAcquire(1L)).isTrue();
        assertThat(rl.tryAcquire(1L)).isTrue();
    }

    @Test
    void fourthCall_denied() {
        EntryRefreshRateLimiter rl = new EntryRefreshRateLimiter();
        rl.tryAcquire(1L);
        rl.tryAcquire(1L);
        rl.tryAcquire(1L);
        assertThat(rl.tryAcquire(1L)).as("窗口内第 4 次必拒").isFalse();
    }

    @Test
    void differentFamiliesNotShareQuota() {
        EntryRefreshRateLimiter rl = new EntryRefreshRateLimiter();
        // family 1 用满
        rl.tryAcquire(1L); rl.tryAcquire(1L); rl.tryAcquire(1L);
        assertThat(rl.tryAcquire(1L)).isFalse();
        // family 2 还能用
        assertThat(rl.tryAcquire(2L)).as("不同 family 独立配额").isTrue();
        assertThat(rl.tryAcquire(2L)).isTrue();
        assertThat(rl.tryAcquire(2L)).isTrue();
        assertThat(rl.tryAcquire(2L)).isFalse();
    }

    @Test
    void secondsUntilNextAllowed_returnsZeroWhenUnderQuota() {
        EntryRefreshRateLimiter rl = new EntryRefreshRateLimiter();
        assertThat(rl.secondsUntilNextAllowed(1L)).isEqualTo(0);
        rl.tryAcquire(1L);
        assertThat(rl.secondsUntilNextAllowed(1L)).isEqualTo(0);
        rl.tryAcquire(1L);
        assertThat(rl.secondsUntilNextAllowed(1L)).isEqualTo(0);
    }

    @Test
    void secondsUntilNextAllowed_returnsPositiveAfterCapHit() {
        EntryRefreshRateLimiter rl = new EntryRefreshRateLimiter();
        rl.tryAcquire(1L); rl.tryAcquire(1L); rl.tryAcquire(1L);
        long wait = rl.secondsUntilNextAllowed(1L);
        assertThat(wait).as("打满后等候时间应 > 0").isGreaterThan(0);
        assertThat(wait).as("窗口 60s · wait 不超过 60s").isLessThanOrEqualTo(EntryRefreshRateLimiter.WINDOW_SECONDS);
    }

    @Test
    void unknownFamily_secondsZero() {
        EntryRefreshRateLimiter rl = new EntryRefreshRateLimiter();
        assertThat(rl.secondsUntilNextAllowed(999L)).isEqualTo(0);
    }

    @Test
    void quotaConstants_are3per60s() {
        // 锁死值 · 防止有人无意改成不合理的(比如 100/s)
        assertThat(EntryRefreshRateLimiter.MAX_PER_WINDOW).isEqualTo(3);
        assertThat(EntryRefreshRateLimiter.WINDOW_SECONDS).isEqualTo(60);
    }
}
