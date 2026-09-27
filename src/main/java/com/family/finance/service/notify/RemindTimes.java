package com.family.finance.service.notify;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v1.26 · 填报提醒「每天几点发」与 cron 的互转。
 *
 * <p>提醒的发送时间(`report_remind_cron`)从 v0.4.18 起就在读配置,但管理页一直没有入口,
 * 只能用代码默认的「每天 10:00 / 20:00」(2026-09-27 护栏 {@code v126-CONFIG-KEY-HAS-HOME} 查出来的缺口)。
 * 提醒页上让人填的是「几点」,不是 cron —— 填提醒的人不该需要懂 cron。</p>
 *
 * <p>同一个人同一个渠道一天只发一次(`report_reminder_log` 唯一键),所以第二个时间是前一次没发出去时的补发,
 * 不会一天收到两条。</p>
 */
public final class RemindTimes {

    /** 代码默认:每天 10:00、20:00 各看一次(调度与页面共用这一份) */
    public static final String DEFAULT_CRON = "0 0 10,20 * * *";
    /** 最多几个时间:再多也只是一天一条,只会白跑 */
    static final int MAX_TIMES = 4;

    private static final Pattern DAILY_HOURS = Pattern.compile("^0 0 ([0-9]{1,2}(?:,[0-9]{1,2})*) \\* \\* \\*$");

    private RemindTimes() { }

    /**
     * 「10,20」「9, 21」「9，21」「8 20」→ {@code 0 0 9,21 * * *}(去重、从早到晚)。
     *
     * @throws IllegalArgumentException 填得不对时带一句人话(页面原样显示)
     */
    public static String toCron(String hours) {
        if (hours == null || hours.isBlank()) throw new IllegalArgumentException("提醒时间至少填一个,例如 10,20");
        TreeSet<Integer> set = new TreeSet<>();
        for (String part : hours.trim().split("[,，、\\s]+")) {
            if (part.isEmpty()) continue;
            String p = part.replaceAll("(点|:00|：00|时)$", "");
            int h;
            try { h = Integer.parseInt(p); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("提醒时间看不懂:「" + part + "」—— 请填 0 到 23 的整点,例如 10,20"); }
            if (h < 0 || h > 23) throw new IllegalArgumentException("提醒时间要在 0 到 23 点之间:「" + part + "」");
            set.add(h);
        }
        if (set.isEmpty()) throw new IllegalArgumentException("提醒时间至少填一个,例如 10,20");
        if (set.size() > MAX_TIMES) throw new IllegalArgumentException("提醒时间最多 " + MAX_TIMES + " 个 —— 同一个人一天只会收到一条,多了只是白跑");
        List<String> hs = new ArrayList<>();
        for (int h : set) hs.add(String.valueOf(h));
        return "0 0 " + String.join(",", hs) + " * * *";
    }

    /**
     * cron → 「10,20」;不是「每天整点」这种简单形式(有人手工配过别的)时返回 {@code null},页面照原样显示那条 cron。
     */
    public static String toHours(String cron) {
        if (cron == null) return null;
        Matcher m = DAILY_HOURS.matcher(cron.trim());
        if (!m.matches()) return null;
        for (String h : m.group(1).split(",")) {
            int v = Integer.parseInt(h);
            if (v < 0 || v > 23) return null;
        }
        return m.group(1);
    }
}
