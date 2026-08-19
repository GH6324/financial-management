package com.family.finance.service.broker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.17.3 · 同步失败的用户可读文案。
 *
 * <p>背景是一次真实的生产事故:富途同步断了两天,而页面上一直显示<b>上一次成功</b>的消息
 * (「同步 · 新增 0 · 更新 7 · 归档 0」)—— 因为失败路径只 {@code log.warn},数据库一个字都不改。
 * 现在失败会落库,这条测试钉住它给出的是<b>人话</b>而不是异常类名。</p>
 */
class BrokerFailureNoteTest {

    @Test
    void connection_refused_reads_like_a_human_wrote_it() {
        String note = BrokerSyncService.failureNote(
                new IllegalStateException("无法发起到 OpenD 的连接 127.0.0.1:11111"));
        assertThat(note).startsWith("同步失败").contains("连不上 OpenD 网关");
        assertThat(note).doesNotContain("IllegalStateException");
    }

    @Test
    void unconfigured_and_timeout_and_login_each_get_their_own_hint() {
        assertThat(BrokerSyncService.failureNote(new IllegalStateException("富途 OpenD 未配置(本关联未填)")))
                .contains("未配置完整");
        assertThat(BrokerSyncService.failureNote(new RuntimeException("read timeout")))
                .contains("超时");
        assertThat(BrokerSyncService.failureNote(new RuntimeException("login failed: bad password")))
                .contains("登录");
    }

    /** 前缀必须是「同步失败」—— 页面靠它切到红色告警样式。 */
    @Test
    void prefix_is_what_the_page_keys_off() {
        assertThat(BrokerSyncService.failureNote(new RuntimeException("whatever"))).startsWith("同步失败 · ");
    }

    /** 带上时间,用户才知道这是最近一次尝试失败,而不是历史某次。 */
    @Test
    void includes_when_it_happened() {
        assertThat(BrokerSyncService.failureNote(new RuntimeException("x"))).containsPattern("\\d{2}-\\d{2} \\d{2}:\\d{2}");
    }

    /** 超长原文要截断 —— 这条会直接显示在卡片上。 */
    @Test
    void very_long_message_is_truncated() {
        String note = BrokerSyncService.failureNote(new RuntimeException("z".repeat(300)));
        assertThat(note.length()).isLessThan(120);
        assertThat(note).contains("…");
    }
}
