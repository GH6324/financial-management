package com.family.finance.service.notify;

import com.family.finance.domain.family.Family;
import com.family.finance.domain.member.Member;

/**
 * v0.4.14 FR-63c · 提醒触达渠道抽象(可插拔)。
 *
 * <p>当前实现:{@link SmsAliyunChannel}(短信为主)/ {@link InAppBannerChannel}(站内兜底)。
 * 新增渠道(邮件 / 微信 / 钉钉)只需实现本接口并注册为 Spring Bean。
 *
 * <p>私密红线:实现类绝不可把 {@link Member#getPhone()} / 短信 aksk 写进
 * 任何 LLM prompt / audit_log 明文 / 普通日志(手机号需掩码,aksk 全程不打印)。
 */
public interface NotificationChannel {

    /** 渠道 code · 写入 report_reminder_log.channel · 与 uk_dedup 联动 */
    String code();

    /** 该家庭此渠道是否可用(配置/开关齐全)· 不可用则调度器跳过该渠道 */
    boolean usable(Family family);

    /**
     * 向单个成员发送一条提醒。
     *
     * @return true=发送成功(或视为已触达);false=失败/跳过 · 调度器据此写 SENT/FAILED 日志
     */
    boolean send(Family family, Member member, ReminderMessage msg);
}
