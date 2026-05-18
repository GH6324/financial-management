package com.family.finance.service.notify;

import com.family.finance.domain.family.Family;
import com.family.finance.domain.member.Member;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * v0.4.14 FR-63c · 站内 banner 渠道(强化兜底 · 始终可用)。
 *
 * <p>真实的"提醒横幅"由填报页 {@code entry/index.html} 按
 * {@code reportingTemplate + 距 period.periodEnd 天数} 实时渲染(见 FR-63b),
 * 本渠道不另起通知表 —— 它的职责是:
 * <ol>
 *   <li>作为永远可用的兜底渠道,保证短信未配时调度链路不空转;</li>
 *   <li>每次调度命中写一行 report_reminder_log(channel=IN_APP)做审计 + 当天去重。</li>
 * </ol>
 */
@Component
@Slf4j
public class InAppBannerChannel implements NotificationChannel {

    public static final String CODE = "IN_APP";

    @Override
    public String code() {
        return CODE;
    }

    /** 站内 banner 不依赖任何外部配置,永远可用。 */
    @Override
    public boolean usable(Family family) {
        return true;
    }

    @Override
    public boolean send(Family family, Member member, ReminderMessage msg) {
        // banner 已由填报页实时渲染,这里仅记审计日志(不含手机号/aksk)
        log.info("in-app reminder armed · family={} member={} daysLeft={}",
                family.getId(), member.getId(), msg.daysLeft());
        return true;
    }
}
