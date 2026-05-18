package com.family.finance.service.notify;

import com.family.finance.domain.family.Family;
import com.family.finance.domain.member.Member;
import com.family.finance.domain.notify.ReportReminderLog;
import com.family.finance.domain.period.Period;
import com.family.finance.repository.MemberMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.PeriodMemberCompletionMapper;
import com.family.finance.repository.ReportReminderLogMapper;
import com.family.finance.service.FamilyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * v0.4.14 FR-63c · 填报截止前强提醒调度器。
 *
 * <p>截止时间统一是 {@code period.periodEnd}(周期结束前必须填完)· 提前
 * {@code family.reportRemindLeadDays} 天(默认 2)进入提醒窗口。
 *
 * <p>cron {@code 0 0 10,20 * * *}(Asia/Shanghai · 每天 10:00 / 20:00 各一次):
 * report_reminder_log 的 UNIQUE(family,period,member,channel,date) 保证
 * 同一成员同一渠道当天只发一次 —— 一天命中 2 次只是容错(早班失败晚班补)。
 *
 * <p>渠道顺序:短信为主([{@link SmsAliyunChannel}])+ 站内 banner 强化兜底
 * ([{@link InAppBannerChannel}] 永远可用)· 两渠道独立去重、独立记日志。
 *
 * <p>私密红线:本类不引用 PromptBuilder · 手机号/aksk 绝不进 LLM/审计明文。
 */
@Component
@Slf4j
public class ReportReminderScheduler {

    private final FamilyService familyService;
    private final PeriodMapper periodMapper;
    private final MemberMapper memberMapper;
    private final PeriodMemberCompletionMapper completionMapper;
    private final ReportReminderLogMapper reminderLogMapper;
    private final List<NotificationChannel> channels;

    public ReportReminderScheduler(FamilyService familyService,
                                   PeriodMapper periodMapper,
                                   MemberMapper memberMapper,
                                   PeriodMemberCompletionMapper completionMapper,
                                   ReportReminderLogMapper reminderLogMapper,
                                   SmsAliyunChannel smsChannel,
                                   InAppBannerChannel inAppChannel) {
        this.familyService = familyService;
        this.periodMapper = periodMapper;
        this.memberMapper = memberMapper;
        this.completionMapper = completionMapper;
        this.reminderLogMapper = reminderLogMapper;
        // 顺序固定:短信为主 → 站内兜底
        this.channels = List.of(smsChannel, inAppChannel);
    }

    @Scheduled(cron = "0 0 10,20 * * *", zone = "Asia/Shanghai")
    public void scheduled() {
        int sent = dispatch(LocalDate.now());
        log.info("report-reminder scheduled run done · armed={}", sent);
    }

    /**
     * 手动触发(管理员 debug · 见 NotificationSettingsController)。
     * @return 本次新触达(写入日志)的条数
     */
    public int runNow() {
        return dispatch(LocalDate.now());
    }

    /** 遍历所有家庭 · 在提醒窗口内对未完成填报成员逐渠道触达(当天去重)。 */
    private int dispatch(LocalDate today) {
        int armed = 0;
        for (Family family : familyService.findAll()) {
            Period period = periodMapper.findCurrentOpen(family.getId()).orElse(null);
            if (period == null) continue;

            long daysLeft = ChronoUnit.DAYS.between(today, period.getPeriodEnd());
            int leadDays = family.getReportRemindLeadDays() == null
                    ? 2 : family.getReportRemindLeadDays();
            // 窗口:截止日前 leadDays 天 ~ 截止日当天([0, leadDays])· 过期不补发
            if (daysLeft < 0 || daysLeft > leadDays) continue;

            Set<Long> completed =
                    new HashSet<>(completionMapper.findCompletedMemberIds(period.getId()));
            List<Member> active = memberMapper.findActiveByFamily(family.getId());
            // 工程算好所有变量给渠道,不让模板做任何计算([[feedback-llm-no-math]] 推广)
            ReminderMessage msg = ReminderMessage.build(
                    family.getName(), family.getBrandText(), period, today,
                    completed.size(), active.size());

            for (Member member : active) {
                if (completed.contains(member.getId())) continue;
                for (NotificationChannel ch : channels) {
                    if (!ch.usable(family)) continue;
                    if (reminderLogMapper.existsToday(family.getId(), period.getId(),
                            member.getId(), ch.code(), today) > 0) continue;

                    boolean ok;
                    try {
                        ok = ch.send(family, member, msg);
                    } catch (Exception ex) {
                        ok = false;
                        log.warn("channel {} threw · family={} member={} err={}",
                                ch.code(), family.getId(), member.getId(), ex.toString());
                    }
                    reminderLogMapper.insert(ReportReminderLog.builder()
                            .familyId(family.getId())
                            .periodId(period.getId())
                            .memberId(member.getId())
                            .channel(ch.code())
                            .remindDate(today)
                            .status(ok ? "SENT" : "FAILED")
                            .detail("daysLeft=" + daysLeft)   // 不含手机号/aksk
                            .build());
                    if (ok) armed++;
                }
            }
        }
        return armed;
    }
}
