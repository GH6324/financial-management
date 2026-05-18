package com.family.finance.service.notify;

import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodType;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.Locale;

/**
 * v0.4.14 FR-63c · 一条填报提醒的渠道无关载荷。
 *
 * <p>4 变量对齐 PRD §20.4(阿里云模板报备时定死格式):
 * <pre>
 *   【家庭账房】${brand}账本 ${period} 提醒:距记账截止${days}天,
 *   家庭已完成 ${progress},请尽快填报。
 * </pre>
 *
 * @param brand        家庭别名(空 fallback 家庭名)· `${brand}` · 短信变量
 * @param period       本期标识(2026年5月 / 第20周)· `${period}` · 短信变量
 * @param daysLeft     距 period.periodEnd 剩余天数(0 = 今天截止)· `${days}` · 短信变量
 * @param progress     全家填报进度(2/4人)· `${progress}` · 短信变量
 * @param renderedText 站内/日志用整句(短信走模板变量不用此串)
 */
public record ReminderMessage(
        String brand,
        String period,
        int daysLeft,
        String progress,
        String renderedText) {

    /**
     * 构造完整 ReminderMessage(给调度器用)。
     * 工程算好所有变量值再喂给渠道,渠道不做任何计算([[feedback-llm-no-math]] 同纪律推广)。
     */
    public static ReminderMessage build(String familyName,
                                        String brandText,
                                        Period currentPeriod,
                                        LocalDate today,
                                        int filledCount,
                                        int activeCount) {
        String brandSafe = (brandText == null || brandText.isBlank())
                ? ((familyName == null || familyName.isBlank()) ? "家庭" : familyName.trim())
                : brandText.trim();
        String periodLabel = formatPeriod(currentPeriod);
        int days = (int) ChronoUnit.DAYS.between(today, currentPeriod.getPeriodEnd());
        String progressLabel = filledCount + "/" + activeCount + "人";
        String rendered = days <= 0
                ? brandSafe + "账本 " + periodLabel + " 提醒:今天是本期记账截止日,家庭已完成 " + progressLabel + ",请尽快填报。"
                : brandSafe + "账本 " + periodLabel + " 提醒:距记账截止还有 " + days + " 天,家庭已完成 " + progressLabel + ",请及时填报。";
        return new ReminderMessage(brandSafe, periodLabel, Math.max(days, 0), progressLabel, rendered);
    }

    /** 测试专用文案(一键测试短信用 · §20.5.1):brand 真实 + 其余写死,运营商日志一眼可辨。 */
    public static ReminderMessage forSmsTest(String familyName, String brandText) {
        String brandSafe = (brandText == null || brandText.isBlank())
                ? ((familyName == null || familyName.isBlank()) ? "家庭" : familyName.trim())
                : brandText.trim();
        return new ReminderMessage(brandSafe, "配置测试", 99, "测试模式",
                brandSafe + "账本 配置测试 提醒:一键测试短信(daysLeft=99 · progress=测试模式)");
    }

    /** 月度 → "2026年5月";周度 → "第20周"(按 ISO 8601 周数)。 */
    private static String formatPeriod(Period p) {
        if (p == null || p.getPeriodStart() == null) return "本期";
        LocalDate start = p.getPeriodStart();
        if (p.getPeriodType() == PeriodType.WEEKLY) {
            int week = start.get(WeekFields.ISO.weekOfWeekBasedYear());
            return "第" + week + "周";
        }
        // MONTHLY 默认
        return start.format(DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINA));
    }
}
