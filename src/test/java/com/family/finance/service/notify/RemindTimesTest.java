package com.family.finance.service.notify;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * v1.26 · 提醒页「每天几点提醒」与 cron 互转。
 *
 * <p>守:用户怎么顺手就怎么填(中文逗号、空格、带「点」)都认;填错说人话、不静默落回默认;
 * 手工配过的自定义 cron 不被页面误显示成整点(否则保存一次就把它覆盖了)。</p>
 */
class RemindTimesTest {

    @Test
    void 默认就是每天10点和20点() {
        assertThat(RemindTimes.toHours(RemindTimes.DEFAULT_CRON)).isEqualTo("10,20");
    }

    @Test
    void 各种顺手的写法都认_去重并从早到晚() {
        assertThat(RemindTimes.toCron("10,20")).isEqualTo("0 0 10,20 * * *");
        assertThat(RemindTimes.toCron("21， 9")).isEqualTo("0 0 9,21 * * *");
        assertThat(RemindTimes.toCron("8 20 8")).isEqualTo("0 0 8,20 * * *");
        assertThat(RemindTimes.toCron("9点、21点")).isEqualTo("0 0 9,21 * * *");
        assertThat(RemindTimes.toCron("0")).isEqualTo("0 0 0 * * *");
    }

    @Test
    void 填错了说人话() {
        assertThatThrownBy(() -> RemindTimes.toCron(" ")).hasMessageContaining("至少填一个");
        assertThatThrownBy(() -> RemindTimes.toCron("24")).hasMessageContaining("0 到 23");
        assertThatThrownBy(() -> RemindTimes.toCron("早上")).hasMessageContaining("看不懂");
        assertThatThrownBy(() -> RemindTimes.toCron("8,10,12,14,16")).hasMessageContaining("最多 4 个");
    }

    @Test
    void 自定义cron不显示成整点_页面照原样给出() {
        assertThat(RemindTimes.toHours("0 30 9 * * *")).isNull();
        assertThat(RemindTimes.toHours("0 0 10 * * MON-FRI")).isNull();
        assertThat(RemindTimes.toHours(null)).isNull();
    }

    @Test
    void 来回转一圈不变() {
        String cron = RemindTimes.toCron("7,12,19");
        assertThat(RemindTimes.toCron(RemindTimes.toHours(cron))).isEqualTo(cron);
    }
}
