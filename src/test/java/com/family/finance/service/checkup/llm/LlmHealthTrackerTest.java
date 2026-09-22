package com.family.finance.service.checkup.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「AI 是不是整体废了」这个判断,穷举。
 *
 * <h3>为什么非要单测不可</h3>
 *
 * <p>这个判据的<b>告警那一半在页面上验不出来</b>:要看到告警,得让所有平台都处于
 * 账户级故障 —— 而那意味着把 beta 的 AI 真的弄坏。所以浏览器只能验「好的时候不报警」,
 * 「坏的时候报警」只能在这里验。</p>
 *
 * <p>而这一条恰恰是整件事的重点:2026-09-22 复盘发现主备双挂了 20 天没人知道,
 * 补这块读数就是为了那个场景。只验好的那一半 = 什么都没验。</p>
 */
class LlmHealthTrackerTest {

    @Test
    @DisplayName("没调用过 ≠ 坏了 —— 不许把「不知道」报成故障")
    void emptyIsNotDown() {
        assertThat(new LlmHealthTracker().allAccountsDown()).isFalse();
    }

    @Test
    @DisplayName("所有平台都账户级故障 → 报警(这正是那 20 天的形状)")
    void allAccountFatal_isDown() {
        var t = new LlmHealthTracker();
        t.recordFail("deepseek", true, "DeepSeek 官方 账户级故障(凭据/欠费/权限)status=402");
        t.recordFail("dashscope", true, "阿里云百炼 账户级故障(凭据/欠费/权限)status=400");
        assertThat(t.allAccountsDown()).isTrue();
    }

    @Test
    @DisplayName("还有一个平台能用 → 不报警(降级到备选是正常工作,不是故障)")
    void oneAlive_isNotDown() {
        var t = new LlmHealthTracker();
        t.recordFail("deepseek", true, "402");
        t.recordOk("dashscope");
        assertThat(t.allAccountsDown()).isFalse();
    }

    /**
     * 判据刻意收紧到「账户级」:超时、限流、单型号额度用尽都会自己恢复。
     * 拿它们报警 = 制造噪音,而噪音久了就没人看 —— 那会把这块读数变成
     * 又一个「一直红着所以被忽略」的东西,等于白做。
     */
    @Test
    @DisplayName("只是偶发失败(超时/限流)→ 不报警")
    void transientFailures_areNotDown() {
        var t = new LlmHealthTracker();
        t.recordFail("deepseek", false, "read timed out");
        t.recordFail("dashscope", false, "429 too many requests");
        assertThat(t.allAccountsDown()).isFalse();
    }

    @Test
    @DisplayName("恢复之后要能自己转回正常 —— 报警不能粘住")
    void recoveryClearsTheAlarm() {
        var t = new LlmHealthTracker();
        t.recordFail("deepseek", true, "402");
        assertThat(t.allAccountsDown()).isTrue();
        t.recordOk("deepseek");
        assertThat(t.allAccountsDown()).isFalse();
        assertThat(t.sinceLastOk()).isNotNull();
    }

    @Test
    @DisplayName("从没成功过 → sinceLastOk 是 null,不编一个数出来")
    void neverOk_hasNoDuration() {
        var t = new LlmHealthTracker();
        t.recordFail("deepseek", true, "402");
        assertThat(t.sinceLastOk()).isNull();
    }

    @Test
    @DisplayName("上游原话要留下来(截断但不丢)—— 不然又变成「我们猜的那句话」")
    void briefIsKept() {
        var t = new LlmHealthTracker();
        t.recordFail("dashscope", true, "Access to model denied. Please make sure you are eligible.");
        assertThat(t.snapshot().get("dashscope").brief()).contains("Access to model denied");
    }
}
