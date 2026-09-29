package com.family.finance.service.llmtrace;

import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v1.28 · 隐私模式也要糊 AI 正文里的金额(PRD FR-913 · tech-design 选型八)。
 *
 * <p>以前隐私模式只糊数字卡片,AI 写的「家庭净资产 ¥6258986」照常显示(v1.27.0 拍发版截图时撞上)。
 * 这里先转义、再把金额包进 {@code <span data-priv>} —— 页面上的隐私模式开关对它一样生效(长按可看)。
 * 前端有一份同一个正则的实现({@code prompt-peek.js} 的 {@code privText}),给 JSON 渲染的 AI 卡片用。</p>
 *
 * <p>认的金额:货币符号开头(¥ ¥ $ US$ HK$ € £)的数字,以及「N 万 / N 亿 / N 元」;数字不以逗号结尾(「¥600,」的逗号是标点)。
 * 百分比、月数、倍数不算金额,不糊。</p>
 */
@Component("aiText")
public class AiText {

    /** 与 prompt-peek.js 的 MONEY 保持一致(单测对同一组样本断言两边切出的一样) */
    public static final String MONEY_REGEX =
            "(?:US\\$|HK\\$|[¥￥$€£])\\s?-?[0-9](?:[0-9,]*[0-9])?(?:\\.[0-9]+)?(?:\\s?[万亿kKwW])?"
          + "|-?[0-9](?:[0-9,]*[0-9])?(?:\\.[0-9]+)?\\s?(?:万元|亿元|万|亿|元)";
    private static final Pattern MONEY = Pattern.compile(MONEY_REGEX);

    /** 转义后把金额包成 {@code <span data-priv>} —— 模板里配 {@code th:utext} 用 */
    public String priv(String text) {
        return privHtml(text);
    }

    public static String privHtml(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        Matcher m = MONEY.matcher(text);
        int last = 0;
        while (m.find()) {
            sb.append(HtmlUtils.htmlEscape(text.substring(last, m.start()), "UTF-8"));
            sb.append("<span data-priv>").append(HtmlUtils.htmlEscape(m.group(), "UTF-8")).append("</span>");
            last = m.end();
        }
        sb.append(HtmlUtils.htmlEscape(text.substring(last), "UTF-8"));
        return sb.toString();
    }
}
