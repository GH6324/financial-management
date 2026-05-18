package com.family.finance.domain.notify;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * v0.4.14 FR-63c · 填报提醒发送审计 + 当天去重。
 *
 * <p>UNIQUE(family_id, period_id, member_id, channel, remind_date) 保证
 * 同一成员同一渠道当天最多发 1 次(cron 一天命中 2 次也只发一次)。
 *
 * <p>私密红线:detail 字段绝不写手机号 / aksk 明文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportReminderLog {
    private Long id;
    private Long familyId;
    private Long periodId;
    private Long memberId;
    /** SMS / IN_APP */
    private String channel;
    private LocalDate remindDate;
    /** SENT / FAILED / SKIPPED */
    private String status;
    private String detail;
    private LocalDateTime sentAt;
}
