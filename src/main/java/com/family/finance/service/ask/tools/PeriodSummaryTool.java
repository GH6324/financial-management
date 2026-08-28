package com.family.finance.service.ask.tools;

import com.family.finance.domain.ask.AskScope;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.period.Period;
import com.family.finance.factview.FactFilter;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.factview.KpiSnapshot;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.service.FamilyService;
import com.family.finance.service.explain.MetricExplainService;
import com.family.finance.service.ask.AskTool;
import com.family.finance.service.ask.AskToolResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * v1.19 · 一期全貌:净资产 / 总资产负债 / 人赚·钱赚·开账基线 / 紧急储备 / 负债率。
 *
 * <p><b>一行计算都不写</b> —— 全部来自 {@link FactViewService},与仪表盘同源。
 * 本类只做三件事:解析账期 → 调既有服务 → 把口径元数据包上去。</p>
 *
 * <p>返回里每个数字都登记成<b>可引用项</b>,并带上「这一期是不是还在进行中」——
 * 进行中的期收支往往没录齐,「钱赚」会偏高,这个警告必须跟着数字一起走,
 * 不能指望模型自己记得。</p>
 */
@Component
@RequiredArgsConstructor
public class PeriodSummaryTool implements AskTool {

    private final FactViewService factViewService;
    private final FamilyService familyService;
    private final PeriodMapper periodMapper;
    /** 引用块要和页面逐字一致,格式化必须用页面那一份 */
    private final MetricExplainService fmt;

    @Override public String name() { return "period_summary"; }

    @Override
    public String description() {
        return "某个账期的全貌:净资产、总资产、总负债、人赚(存下的)、钱赚(投资损益)、开账基线、"
             + "紧急储备月数、负债率。不传 period 就用最新一期。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of("type", "object",
                "properties", Map.of("period",
                        Map.of("type", "string", "description", "账期,格式 yyyy-MM;不传用最新一期")),
                "required", List.of());
    }

    @Override public AskScope requiredScope() { return AskScope.AGGREGATE; }

    @Override
    public AskToolResult execute(long familyId, Map<String, Object> args) {
        Family family = familyService.require(familyId);
        String want = args.get("period") == null ? null : String.valueOf(args.get("period")).trim();

        List<Period> all = periodMapper.findAllByFamily(familyId).stream()
                .filter(p -> p.getPeriodStart() != null)
                .sorted(Comparator.comparing(Period::getPeriodStart))
                .toList();
        if (all.isEmpty()) return AskToolResult.failed(name(), "这个家庭还没有任何账期");

        Period anchor = null;
        if (want != null && !want.isBlank()) {
            for (Period p : all) {
                if (p.getPeriodStart().toString().startsWith(want)) { anchor = p; break; }
            }
            if (anchor == null) {
                throw new AskParamException("没有 " + want + " 这个账期",
                        Map.of("periods", all.stream()
                                .map(p -> p.getPeriodStart().toString().substring(0, 7)).toList()));
            }
        } else {
            anchor = resolveDefaultPeriod(all);
        }

        LocalDate end = anchor.getPeriodStart();
        FactSlice slice = factViewService.load(new FactFilter(
                familyId, family.getPeriodType(), end.minusMonths(12), end, false, null,
                family.getBaseCurrency()));
        KpiSnapshot k = factViewService.kpis(slice);

        boolean inProgress = !"CLOSED".equals(String.valueOf(anchor.getStatus()));
        String label = end.toString().substring(0, 7);
        String cur = family.getBaseCurrency();

        AskToolResult.Builder b = AskToolResult.of(name())
                .put("period", label)
                .put("inProgress", inProgress)
                .put("netWorth", plain(k.netWorth()))
                .put("totalAssets", plain(k.totalAssets()))
                .put("totalLiabilities", plain(k.totalLiabilities()))
                .put("netWorthDelta", plain(k.netWorthDelta()))
                .put("humanEarned", plain(k.lastNetInflow()))
                .put("openingBaseline", plain(k.openingBaselineLast()))
                .put("emergencyMonths", plain(k.emergencyFundMonths()))
                .put("debtToAssetRatio", plain(k.debtToAssetRatio()));

        cite(b, "nw", "kpi.netWorth", "净资产", k.netWorth(), anchor.getId(), inProgress, cur, "/dashboard");
        cite(b, "ta", "kpi.totalAssets", "总资产", k.totalAssets(), anchor.getId(), inProgress, cur, "/dashboard");
        cite(b, "tl", "kpi.totalLiabilities", "总负债", k.totalLiabilities(), anchor.getId(), inProgress, cur, "/dashboard");
        cite(b, "dn", "kpi.netWorthDelta", "净资产变化", k.netWorthDelta(), anchor.getId(), inProgress, cur, "/dashboard#dash-cashflow");
        cite(b, "he", "kpi.humanEarned", "人赚(你存下的)", k.lastNetInflow(), anchor.getId(), inProgress, cur, "/dashboard#dash-cashflow");
        cite(b, "ob", "kpi.openingBaseline", "开账基线", k.openingBaselineLast(), anchor.getId(), inProgress, cur, "/dashboard#dash-cashflow");
        citeMonths(b, "em", "紧急储备月数", k.emergencyFundMonths(), anchor.getId(), inProgress);

        if (inProgress) {
            b.metaExtra("warning",
                    label + " 这一期还没关账。收支通常还没录齐,「钱赚」会偏高、「人赚」会偏低 —— "
                  + "引用这一期的数时必须把这句说给用户听。");
        }
        b.summary(label + (inProgress ? " · 进行中" : " · 已关账") + " · 净资产/总资产/人赚 等 8 项");
        return b.meta(anchor.getId(), label, inProgress, "kpi.periodSummary", cur).build();
    }

    /**
     * 默认账期 —— <b>不能简单取「最新一期」</b>。
     *
     * <p>账期表可能预建到很多年以后(beta 上排到了 2041),取 max 会锚到一个
     * <b>未来的空账期</b>:余额是结转来的、收支全零,而且状态还是「已关账」——
     * 从数字上完全看不出异常,agent 会拿它当真话讲出去。</p>
     *
     * <p>所以照抄仪表盘的锚点规则:<b>优先当前进行中的期;否则取最近一个「已经开始」的期</b>
     * (period_start ≤ 今天);都没有再退 max。口径与页面一致,是这一版最重的承诺。</p>
     */
    static Period resolveDefaultPeriod(List<Period> ascending) {
        LocalDate today = LocalDate.now();
        Period started = null;
        for (Period p : ascending) {
            if (!p.getPeriodStart().isAfter(today)) started = p;      // 升序,最后一个即最近
            if (!"CLOSED".equals(String.valueOf(p.getStatus()))
                    && !p.getPeriodStart().isAfter(today)) {
                // 进行中且已开始 —— 优先它
                started = p;
            }
        }
        return started != null ? started : ascending.get(ascending.size() - 1);
    }

    /**
     * 金额型引用。
     *
     * <p>{@code data} 里给模型的仍是原始数值(它要比大小),<b>但用户看到的这一份带货币符号和千分位</b> ——
     * 「1234567.89」和页面上的「¥1,234,568」是同一个数,可用户得自己在心里加逗号才敢确认,
     * 而这个功能的全部意义就是让他不用怀疑。格式化走 {@link MetricExplainService},与页面同一份实现。</p>
     */
    private void cite(AskToolResult.Builder b, String key, String metricKey, String label,
                      BigDecimal v, Long periodId, boolean inProgress, String cur, String href) {
        if (v == null) return;
        b.cite(key, metricKey, label, fmt.money(cur, v), periodId, inProgress, cur, href);
    }

    /** 月数不是金额,别套货币符号 */
    private void citeMonths(AskToolResult.Builder b, String key, String label,
                            BigDecimal v, Long periodId, boolean inProgress) {
        if (v == null) return;
        b.cite(key, "kpi.emergencyMonths", label, fmt.months(v), periodId, inProgress,
                null, "/checkup#liquidity");
    }

    private static String plain(BigDecimal v) { return v == null ? null : v.toPlainString(); }
}
