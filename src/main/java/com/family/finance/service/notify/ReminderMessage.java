package com.family.finance.service.notify;

import com.family.finance.domain.period.Period;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

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
     *
     * <p><b>Aliyun 模板变量类型校验妥协</b>(2026-05-18):用户审批通过的模板把
     * {@code ${period}} 标为「时间」类型、{@code ${progress}} 标为「金额/数量」类型,
     * 所以这里:
     * <ul>
     *   <li>{@code period} 用 {@code period.periodEnd} 的 yyyy-MM-dd 格式(过「时间」校验)</li>
     *   <li>{@code progress} 用整数百分比 0-100(过「金额/数量」校验)</li>
     * </ul>
     * 牺牲一点可读性换不重申模板。后续用户重审为「全文本」类型模板后,切回
     * 「2026年5月」/「2/4人」更可读形式。
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
        // period:periodEnd 的 yyyy-MM-dd · 过 Aliyun「时间」校验
        String periodLabel = currentPeriod.getPeriodEnd().format(DateTimeFormatter.ISO_LOCAL_DATE);
        int days = (int) ChronoUnit.DAYS.between(today, currentPeriod.getPeriodEnd());
        // progress:完成百分比整数 0-100 · 过 Aliyun「金额/数量」校验
        int pct = activeCount <= 0
                ? 0
                : Math.min(100, Math.round(100.0f * filledCount / activeCount));
        String progressLabel = String.valueOf(pct);
        String rendered = days <= 0
                ? brandSafe + "账本 " + periodLabel + " 提醒:今天是本期记账截止日,家庭已完成 " + progressLabel + "%,请尽快填报。"
                : brandSafe + "账本 " + periodLabel + " 提醒:距记账截止还有 " + days + " 天,家庭已完成 " + progressLabel + "%,请及时填报。";
        return new ReminderMessage(brandSafe, periodLabel, Math.max(days, 0), progressLabel, rendered);
    }

    /**
     * 测试专用文案(一键测试短信用 · §20.5.1)· 同样过 Aliyun 变量类型校验:
     * <ul>
     *   <li>period:<b>真实账期 periodEnd</b> 的 yyyy-MM-dd(本期截止日 · 过「时间」校验)</li>
     *   <li>days:<b>-1</b>(测试标识 · 不是真实窗口 · 运营商日志可辨)· 过「金额/数量」校验(允许负数)</li>
     *   <li>progress:100(测试满)</li>
     * </ul>
     */
    public static ReminderMessage forSmsTest(String familyName, String brandText, Period currentPeriod) {
        String brandSafe = (brandText == null || brandText.isBlank())
                ? ((familyName == null || familyName.isBlank()) ? "家庭" : familyName.trim())
                : brandText.trim();
        String periodLabel = currentPeriod.getPeriodEnd().format(DateTimeFormatter.ISO_LOCAL_DATE);
        return new ReminderMessage(brandSafe, periodLabel, -1, "100",
                brandSafe + "账本 " + periodLabel + " 提醒:一键测试短信(days=-1 标识测试 · progress=100 测试满)");
    }
}
