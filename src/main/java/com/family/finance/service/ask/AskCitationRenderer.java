package com.family.finance.service.ask;

import com.family.finance.domain.ask.AskCitation;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.domain.period.Period;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v1.19 · 把 {@code {{cite:c1}}} 标记渲染成引用块。
 *
 * <h3>为什么正文里存的是标记而不是数字</h3>
 * <p>模型没有机会碰那个数字 —— 它只写标记,数值从 {@link AskCitation} 取,
 * 而那是工具返回的原值。「模型把 760 万说成 706 万」这类错误在结构上不可能发生。</p>
 *
 * <h3>口径文案是渲染期现取的</h3>
 * <p>数值是<b>当时</b>的(存在库里),口径说法是<b>今天</b>的(在代码里)。
 * 于是三个月前那条回答重新打开时,数字仍是当时那个,但「这个数怎么算的」跟着最新说法走 ——
 * 不会出现一个已经改过名的旧指标名孤零零地留在历史里。</p>
 *
 * <p><b>取舍说明</b>:口径文案这里给的是<b>静态一句话</b>,不是页面 tooltip 里那种
 * 「A − B = C」的实算展开 —— 后者要重跑整条 KPI 管线才拿得到,为了渲染一条三个月前的
 * 历史消息重算一遍全家资产不划算。想看实算的,点引用块回原页,那里是活的。</p>
 */
@Service
@RequiredArgsConstructor
public class AskCitationRenderer {

    private static final Pattern CITE = Pattern.compile("\\{\\{cite:([A-Za-z0-9_]{1,14})}}");
    /** 正文里出现的裸金额 —— 用来判定「模型没听话,自己抄数字了」 */
    private static final Pattern BARE_MONEY =
            Pattern.compile("(?<![\\d.])\\d{1,3}(,\\d{3})+(\\.\\d+)?(?![\\d.])|(?<![\\d.])\\d{5,}(\\.\\d+)?(?![\\d.])");

    private final PeriodMapper periodMapper;

    /**
     * metricKey → 这个数字叫什么、怎么来的、点回哪、那一页叫什么。
     *
     * <p>{@code go} 是<b>去向短标签</b>,不是口径说明。卡片右下角那一行每张卡都会出现,
     * 放整句口径的话四张卡就是四遍同样的话 —— 手机上尤其吵。完整口径进 tooltip,
     * 那儿是用户主动问「这怎么算的」时才看的。</p>
     */
    private record Meta(String label, String explain, String href, String go) {}

    private static final Map<String, Meta> METAS = new LinkedHashMap<>();
    static {
        METAS.put("kpi.netWorth", new Meta("净资产",
                "总资产减去总负债。房子按你填的估值算,不含未来要还的部分。", "/dashboard", "→ 仪表盘"));
        METAS.put("kpi.totalAssets", new Meta("总资产",
                "所有资产类账户的期末余额合计,按视图币种折算。", "/dashboard", "→ 仪表盘"));
        METAS.put("kpi.totalLiabilities", new Meta("总负债",
                "所有负债类账户的期末余额绝对值合计。", "/dashboard", "→ 仪表盘"));
        METAS.put("kpi.netWorthDelta", new Meta("净资产变化",
                "本期净资产减上期净资产。它同时包含「你存下的」和「投资赚的」。", "/dashboard#dash-cashflow", "→ 仪表盘 · 本期怎么变的"));
        METAS.put("kpi.humanEarned", new Meta("人赚(你存下的)",
                "本期净流入 —— 收入减支出。跟市场涨跌无关,是你自己攒下来的部分。",
                "/dashboard#dash-cashflow", "→ 仪表盘 · 本期怎么变的"));
        METAS.put("kpi.openingBaseline", new Meta("开账基线",
                "第一期开账时已有的存量,不算在任何一期的收益里 —— 否则第一期会凭空多出一大笔。",
                "/dashboard#dash-cashflow", "→ 仪表盘 · 本期怎么变的"));
        METAS.put("kpi.emergencyMonths", new Meta("紧急储备月数",
                "随时能取用的钱,够覆盖几个月的平均支出。", "/checkup#liquidity", "→ 资产体检 · 流动性"));
        METAS.put("lens.pivot", new Meta("资产分布",
                "按你选的维度把账户余额归堆,合计等于总资产。", "/lens", "→ 资产透视"));
        METAS.put("factview.accountPerformance", new Meta("账户表现",
                "单个账户的现值、累计投入与收益率。满 12 期才是年化,不足 12 期是累计。",
                "/reports#rep-accounts", "→ 报表 · 账户表现"));
    }

    /**
     * 补上 label / explain,给模板用。
     *
     * <p><b>label 优先用落库那一份</b> —— 它常常是数据派生的(「支付宝 · 总资产」里的行名
     * 来自用户自己的账户),口径表里推不出来。只有它缺失时才退回口径表的通名。</p>
     */
    public AskCitation decorate(AskCitation c) {
        Meta m = lookup(c.getMetricKey());
        if (c.getLabel() == null || c.getLabel().isBlank()) {
            c.setLabel(m == null ? c.getMetricKey() : m.label());
        }
        c.setExplain(m == null ? "这个数来自系统的既有口径,与页面上同名指标一致。" : m.explain());
        if ((c.getTargetHref() == null || c.getTargetHref().isBlank()) && m != null) {
            c.setTargetHref(m.href());
        }
        return c;
    }

    /**
     * 按最长前缀找口径。
     *
     * <p>工具发出的 metricKey 常常带度量后缀({@code lens.pivot.value} / {@code lens.pivot.share}),
     * 精确匹配会全部落到兜底文案上 —— 界面上就是四张卡片写着同一句废话。
     * 逐段回退到 {@code lens.pivot} 就能拿到真正的口径说明。</p>
     */
    private static Meta lookup(String metricKey) {
        if (metricKey == null) return null;
        String k = metricKey;
        while (true) {
            Meta m = METAS.get(k);
            if (m != null) return m;
            int dot = k.lastIndexOf('.');
            if (dot < 0) return null;
            k = k.substring(0, dot);
        }
    }

    public String toolLabel(String toolName) {
        return switch (toolName) {
            case "capabilities" -> "看看能查什么";
            case "pivot" -> "资产分布";
            case "period_summary" -> "账期全貌";
            case "account_performance" -> "账户收益";
            case "report_unmet" -> "记一笔够不着";
            default -> toolName;
        };
    }

    public String periodLabel(Long periodId) {
        if (periodId == null) return null;
        return periodMapper.findById(periodId)
                .map(Period::getPeriodStart)
                .map(d -> d.toString().substring(0, 7))
                .orElse(null);
    }

    // ──────────────────────── 渲染 ────────────────────────

    /**
     * 正文 → HTML。
     *
     * <p>先转义再替换标记 —— 顺序反了就等于把模型的输出当 HTML 执行。
     * 模型的输出是不可信输入,哪怕它是我们自己配的模型:提示词注入可以从用户的账户名里来。</p>
     */
    public String renderHtml(String body, List<AskCitation> citations) {
        if (body == null) return "";
        Map<String, AskCitation> byKey = new LinkedHashMap<>();
        for (AskCitation c : citations) byKey.put(c.getCiteKey(), decorate(c));

        StringBuilder out = new StringBuilder();
        for (String block : escape(body).split("\n{2,}")) {
            if (block.isBlank()) continue;
            for (String line : block.split("\n")) {
                if (line.isBlank()) continue;
                String t = line.trim();
                Matcher only = CITE.matcher(t);
                // 整行只有一个标记 → 独立的引用卡(审过的预览就是这个形态)
                if (only.matches()) {
                    AskCitation c = byKey.get(only.group(1));
                    if (c != null) out.append(card(c));
                    continue;
                }
                out.append("<p>").append(inline(t, byKey)).append("</p>");
            }
        }
        return out.toString();
    }

    /** 行内残留的标记:退成一个紧凑 chip,不至于整句话中间插一张卡 */
    private String inline(String line, Map<String, AskCitation> byKey) {
        StringBuilder sb = new StringBuilder();
        Matcher m = CITE.matcher(line);
        while (m.find()) {
            AskCitation c = byKey.get(m.group(1));
            String rep = c == null ? "" :
                    "<a class=\"ask-chip\" href=\"" + escape(href(c)) + "\" title=\""
                  + escape(c.getExplain()) + "\">" + escape(c.getValueText())
                  + (c.isInProgress() ? "<i>未关账</i>" : "") + "</a>";
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        // 极简 markdown:模型输出里常见的就 **粗体**,不为它引一个解析器
        return sb.toString().replaceAll("\\*\\*([^*]{1,80})\\*\\*", "<strong>$1</strong>");
    }

    /**
     * 一张引用卡:指标名 + 数值 + 账期与关账状态 + 点回原页。
     *
     * <p>四样缺一不可。缺指标名,用户不知道这是什么;缺账期,数字说不清自己是哪一期的;
     * 缺关账状态,进行中的期会被当成定论;缺链接,用户没法自己核 —— 而「能自己核」
     * 是这个功能敢让 AI 碰资产数据的前提。</p>
     */
    private String card(AskCitation c) {
        String per = periodLabel(c.getPeriodId());
        StringBuilder s = new StringBuilder();
        // 完整口径进 title —— 用户想知道「这怎么算的」时才看,不占卡片版面
        s.append("<a class=\"ask-cite\" href=\"").append(escape(href(c)))
         .append("\" title=\"").append(escape(c.getExplain())).append("\">");
        s.append("<span class=\"ask-cite-top\"><span class=\"ask-cite-k\">")
         .append(escape(c.getLabel())).append("</span><span class=\"ask-cite-v\">")
         .append(escape(c.getValueText())).append("</span></span>");
        s.append("<span class=\"ask-cite-meta\"><span class=\"ask-cite-per\">");
        if (per != null) s.append(escape(per));
        if (c.isInProgress()) s.append(per == null ? "未关账" : " · 未关账");
        s.append("</span><span class=\"ask-cite-go\">").append(escape(goOf(c)))
         .append("</span></span>");
        s.append("</a>");
        return s.toString();
    }

    /** 去向短标签;口径表里没有就只写「去核对」——总比空着强 */
    private static String goOf(AskCitation c) {
        Meta m = lookup(c.getMetricKey());
        return m == null || m.go() == null ? "→ 去核对" : m.go();
    }

    private static String href(AskCitation c) {
        return c.getTargetHref() == null || c.getTargetHref().isBlank() ? "#" : c.getTargetHref();
    }

    /**
     * 正文里有没有裸金额 —— 有就说明模型没按规矩用引用块。
     *
     * <p>不拦截、不重写,只标灰并提示「这个数没有出处,去页面上核一下」。
     * 拦下来重问一次成本翻倍,而且它多半还会再犯;标出来让用户知道哪个数可信、
     * 哪个数要自己核,比假装没发生有用。护栏 {@code v119-ASK-NO-BARE-NUMBER} 盯着这条。</p>
     */
    public boolean hasBareNumber(String body) {
        if (body == null || body.isBlank()) return false;
        String withoutCites = CITE.matcher(body).replaceAll("");
        return BARE_MONEY.matcher(withoutCites).find();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
