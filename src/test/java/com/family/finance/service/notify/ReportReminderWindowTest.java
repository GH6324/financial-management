package com.family.finance.service.notify;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v0.10.1 护栏(v10-REMIND-1)· 短信/站内提醒窗口边界。
 *
 * <p>纪律:lead=N 应是「截止日当天 + 前 N-1 天」= 共 <b>N</b> 个提醒日,绝不多发。
 * 历史 bug:窗口写成 [0, lead] 共 lead+1 天 → lead=2、截止 6.30 时误发 6.28/6.29/6.30 三天。</p>
 */
class ReportReminderWindowTest {

    @Test
    void lead2_remindsExactlyTwoDays_deadlineAndDayBefore() {
        // 截止 6.30:6.30→daysLeft0、6.29→1、6.28→2
        assertThat(ReportReminderScheduler.inReminderWindow(0, 2)).as("6.30 截止日当天").isTrue();
        assertThat(ReportReminderScheduler.inReminderWindow(1, 2)).as("6.29 前一天").isTrue();
        assertThat(ReportReminderScheduler.inReminderWindow(2, 2)).as("6.28 不应再发(原 bug 多发的那天)").isFalse();
    }

    @Test
    void lead1_onlyDeadlineDay() {
        assertThat(ReportReminderScheduler.inReminderWindow(0, 1)).isTrue();
        assertThat(ReportReminderScheduler.inReminderWindow(1, 1)).isFalse();
    }

    @Test
    void lead3_threeDays() {
        assertThat(ReportReminderScheduler.inReminderWindow(0, 3)).isTrue();
        assertThat(ReportReminderScheduler.inReminderWindow(1, 3)).isTrue();
        assertThat(ReportReminderScheduler.inReminderWindow(2, 3)).isTrue();
        assertThat(ReportReminderScheduler.inReminderWindow(3, 3)).isFalse();
    }

    @Test
    void expired_neverReminds() {
        assertThat(ReportReminderScheduler.inReminderWindow(-1, 2)).isFalse();
        assertThat(ReportReminderScheduler.inReminderWindow(-5, 5)).isFalse();
    }

    @Test
    void windowSize_equalsLeadDays() {
        for (int lead = 1; lead <= 7; lead++) {
            int hits = 0;
            for (long d = -3; d <= 15; d++) if (ReportReminderScheduler.inReminderWindow(d, lead)) hits++;
            assertThat(hits).as("lead=" + lead + " 应恰好 " + lead + " 个提醒日").isEqualTo(lead);
        }
    }
}
