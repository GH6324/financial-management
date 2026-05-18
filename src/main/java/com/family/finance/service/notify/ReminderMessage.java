package com.family.finance.service.notify;

/**
 * v0.4.14 FR-63c · 一条填报提醒的渠道无关载荷。
 *
 * @param brandText    家庭别名(如「狗窝」)· 短信正文用 · 取 family.brandText,空则 family.name
 * @param daysLeft     距本期填报截止(period.periodEnd)还剩几天 · 0 = 今天截止
 * @param renderedText 已渲染的中文整句 · 站内 banner / 日志摘要用(短信走模板参数不用此串)
 */
public record ReminderMessage(String brandText, int daysLeft, String renderedText) {

    /** 站内/日志用整句 · 短信由运营商按报备模板渲染,不用这句。 */
    public static ReminderMessage of(String brandText, int daysLeft) {
        String brand = (brandText == null || brandText.isBlank()) ? "家庭" : brandText.trim();
        String body = daysLeft <= 0
                ? brand + "账本提醒您:今天是本期记账截止日,请尽快填报。"
                : brand + "账本提醒您:距本期记账截止还有 " + daysLeft + " 天,请及时填报。";
        return new ReminderMessage(brand, daysLeft, body);
    }
}
