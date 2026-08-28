package com.family.finance.service.ask.tools;

import com.family.finance.calc.lens.LensQuery;
import com.family.finance.calc.lens.LensRegistry;
import com.family.finance.calc.lens.PivotEngine;
import com.family.finance.calc.lens.Position;
import com.family.finance.domain.ask.AskScope;
import com.family.finance.service.ask.AskTool;
import com.family.finance.service.ask.AskToolResult;
import com.family.finance.domain.period.Period;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.service.FamilyService;
import com.family.finance.service.explain.MetricExplainService;
import com.family.finance.service.lens.LensQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v1.19 · 主力查询 —— <b>把资产透视页背后的语义层整个交给 agent</b>。
 *
 * <h3>自由度从哪来</h3>
 * <p>不是「开放原始数据」,是复用已有的 {@link LensQuery} + {@link PivotEngine}:
 * 11 个维度 × 7 个度量 × 任意筛选 × 行列交叉,组合空间远超我们能想到的罐头报表。</p>
 *
 * <h3>为什么这样还能守住口径</h3>
 * <p><b>聚合权在我们手里</b>:agent 说「按平台分组」,是 {@code PivotEngine} 去分组;
 * 它拿到的已经是分好组、算好占比、带小计的结果 —— <b>没有算术可做</b>。
 * 于是那条「LLM 禁止四则运算」的铁律不再是限制,而是<b>不必要</b>。</p>
 *
 * <h3>行数上限为什么是 50 不是 500</h3>
 * <p>成本测算的结论:500 行约 15,000 token 一次,单轮成本涨 9 倍;
 * 而 agent 读不完也讲不清,最终只挑几行说 —— 前面一万多 token 白花。
 * 默认 50、最多 200,超出<b>显式标注被截断</b>并提示改用筛选,不静默。</p>
 */
@Component
@RequiredArgsConstructor
public class PivotTool implements AskTool {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    /**
     * 发多少行的可引用值。
     *
     * <p>不是行数上限(那是 {@link #DEFAULT_LIMIT}),是「模型能精确引用的行数」。
     * 12 行覆盖了绝大多数分组问题(平台、类型、托管形式、主理人都在这个量级),
     * 再多是给一屏念不完的长尾发引用,纯浪费 token。</p>
     */
    public static final int ROW_CITES = 12;
    /** 行 + 列合计层数上限:再深读不出结构,只会把 token 花在没人看的嵌套上 */
    public static final int MAX_DEPTH = 3;

    private final LensQueryService lensQueryService;
    private final FamilyService familyService;
    private final PeriodMapper periodMapper;
    /** 引用块里的数字要和页面上<b>逐字一致</b>,所以格式化必须用页面那一份,不能自己写 */
    private final MetricExplainService fmt;

    @Override public String name() { return "pivot"; }

    @Override
    public String description() {
        return "按任意维度交叉查询家庭资产。行/列/度量/筛选可自由组合;分组、小计、占比都由系统算好,"
             + "你直接引用即可,不要自己做加减。维度与取值先用 capabilities 查,别猜。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        List<String> dims = new ArrayList<>(LensRegistry.DIMENSIONS.keySet());
        List<String> measures = new ArrayList<>(LensRegistry.MEASURES.keySet());
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("rows", Map.of("type", "array",
                "items", Map.of("type", "string", "enum", dims),
                "description", "行维度,可嵌套多个"));
        props.put("cols", Map.of("type", "array",
                "items", Map.of("type", "string", "enum", dims),
                "description", "列维度,可空"));
        props.put("measures", Map.of("type", "array",
                "items", Map.of("type", "string", "enum", measures),
                "description", "度量,缺省 value + share"));
        props.put("filters", Map.of("type", "object",
                "description", "维度到取值数组的映射,例如 owner 对应 [\"成员A\"]"));
        props.put("limit", Map.of("type", "integer",
                "description", "返回行数上限,默认 " + DEFAULT_LIMIT + ",最多 " + MAX_LIMIT));
        return Map.of("type", "object", "properties", props, "required", List.of("rows"));
    }

    @Override public AskScope requiredScope() { return AskScope.AGGREGATE; }

    @Override
    public AskToolResult execute(long familyId, Map<String, Object> args) {
        List<String> rows = strings(args.get("rows"));
        List<String> cols = strings(args.get("cols"));
        List<String> measures = strings(args.get("measures"));
        if (measures.isEmpty()) measures = List.of("value", "share");

        // 参数不合法时把【可用取值】一起回给模型,让它自己改 —— 这也是自由度的一部分
        validateDims(rows, "rows");
        validateDims(cols, "cols");
        validateMeasures(measures);
        if (rows.isEmpty()) {
            throw new AskParamException("rows 至少要给一个维度", allowedMap());
        }
        if (rows.size() + cols.size() > MAX_DEPTH) {
            throw new AskParamException(
                    "行加列的维度合计最多 " + MAX_DEPTH + " 层 —— 再深读不出结构,建议改用 filters 收窄",
                    allowedMap());
        }

        Map<String, List<String>> filters = new LinkedHashMap<>();
        if (args.get("filters") instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (!LensRegistry.DIMENSIONS.containsKey(k)) {
                    throw new AskParamException("筛选用了不存在的维度:" + k, allowedMap());
                }
                filters.put(k, strings(e.getValue()));
            }
        }

        int limit = args.get("limit") instanceof Number n
                ? Math.min(MAX_LIMIT, Math.max(1, n.intValue())) : DEFAULT_LIMIT;

        List<Position> positions = lensQueryService.positions(familyId);
        PivotEngine.Result r = PivotEngine.pivot(positions, new LensQuery(rows, cols, measures, filters));

        boolean truncated = r.rowKeys().size() > limit;
        List<List<String>> keys = truncated ? r.rowKeys().subList(0, limit) : r.rowKeys();
        Set<String> keep = new HashSet<>();
        for (List<String> k : keys) keep.add(String.join("", k));

        List<Map<String, Object>> cells = new ArrayList<>();
        for (PivotEngine.Cell c : r.cells()) {
            if (!keep.contains(String.join("", c.row()))) continue;
            cells.add(Map.of("row", c.row(), "col", c.col(), "values", plain(c.values())));
        }

        AskToolResult.Builder b = AskToolResult.of(name())
                .put("rowDims", r.rowDims()).put("colDims", r.colDims())
                .put("measures", r.measures())
                .put("rowKeys", keys).put("colKeys", r.colKeys())
                .put("cells", cells)
                .put("grand", plain(r.grand()))
                .put("holdingLevelSplit", r.holdingLevelSplit());

        if (truncated) {
            b.put("truncated", Map.of(
                    "shown", keys.size(),
                    "total", r.rowKeys().size(),
                    "hint", "结果被截断了。想看全部请加 filters 收窄,或换更粗的维度,不要靠加大 limit 硬看"));
        }
        if (r.holdingLevelSplit()) {
            b.metaExtra("warning",
                    "本次查询含持仓级维度:收益类度量按持有口径计,不可精确归因到账户。讲结论时要把这一点说出来。");
        }

        // ── 可引用的数字 ──
        // 合计 + **每一行**。只给合计是不够的:联调时模型拿到「总资产合计」这一个可引用项,
        // 讲支付宝占多少时无处可引,于是退化成「将近一半」「各占一成左右」这种约数,
        // 而那正是这个功能要消灭的东西。给它每行的精确值,它才有得可引。
        String ccy = familyService.require(familyId).getBaseCurrency();
        Long anchorId = lensQueryService.anchorPeriodId(familyId);
        Period anchorP = anchorId == null ? null : periodMapper.findById(anchorId).orElse(null);
        boolean anchorOpen = anchorP != null && !"CLOSED".equals(String.valueOf(anchorP.getStatus()));
        List<BigDecimal> grand = r.grand();
        for (int i = 0; i < r.measures().size() && grand != null && i < grand.size(); i++) {
            String mk = r.measures().get(i);
            BigDecimal v = grand.get(i);
            b.cite("g" + i, "lens.pivot." + mk, measureLabel(mk) + " 合计",
                    display(mk, v, ccy), anchorId, anchorOpen, ccy, "/lens");
        }

        // 行级引用只在「单层行维、无列维」时给 —— 多维交叉时行名是组合键,
        // 逐格发引用会把 token 撑爆,而模型真正会逐个念的也就是一层分组那种问题。
        if (rows.size() == 1 && cols.isEmpty()) {
            int n = 0;
            for (PivotEngine.Cell c : r.cells()) {
                if (n >= ROW_CITES) break;
                if (!keep.contains(String.join("", c.row()))) continue;
                String rowName = String.join(" / ", c.row());
                for (int i = 0; i < r.measures().size() && i < c.values().size(); i++) {
                    BigDecimal v = c.values().get(i);
                    if (v == null) continue;
                    String mk = r.measures().get(i);
                    b.cite("r" + n + "_" + i, "lens.pivot." + mk,
                            rowName + " · " + measureLabel(mk),
                            display(mk, v, ccy), anchorId, anchorOpen, ccy, "/lens");
                }
                n++;
            }
            if (r.cells().size() > ROW_CITES) {
                b.metaExtra("citeNote", "只有前 " + ROW_CITES + " 行给了可引用值。"
                        + "要讲后面的行,请先用 filters 收窄再问一次,不要凭 cells 里的数自己念。");
            }
        }

        // 账期必须给全 —— 空的 period 会让 agent 停下来专门问「这是哪一期的数」,
        // 实测里它为此多花了一整轮,还把疑问写进了给用户的回答。
        String label = anchorP == null || anchorP.getPeriodStart() == null ? null
                : anchorP.getPeriodStart().toString().substring(0, 7);
        if (anchorOpen) {
            b.metaExtra("warning", label + " 还没关账,余额可能还没录齐。引用这一期的数时要说这句。");
        }
        return b.meta(anchorId, label, anchorOpen, "lens.pivot", ccy).build();
    }

    private void validateDims(List<String> ds, String field) {
        for (String d : ds) {
            if (!LensRegistry.DIMENSIONS.containsKey(d)) {
                throw new AskParamException(field + " 里有不存在的维度:" + d, allowedMap());
            }
        }
    }

    private void validateMeasures(List<String> ms) {
        for (String m : ms) {
            if (!LensRegistry.MEASURES.containsKey(m)) {
                throw new AskParamException("不存在的度量:" + m, allowedMap());
            }
        }
    }

    private Map<String, Object> allowedMap() {
        return Map.of(
                "dimensions", new ArrayList<>(LensRegistry.DIMENSIONS.keySet()),
                "measures", new ArrayList<>(LensRegistry.MEASURES.keySet()),
                "hint", "先调 capabilities 看每个维度的实际取值");
    }

    private static List<String> strings(Object o) {
        if (o == null) return List.of();
        if (o instanceof List<?> l) return l.stream().map(String::valueOf).toList();
        return List.of(String.valueOf(o));
    }

    private static List<String> plain(List<BigDecimal> vs) {
        if (vs == null) return List.of();
        List<String> out = new ArrayList<>(vs.size());
        for (BigDecimal v : vs) out.add(v == null ? "—" : v.toPlainString());
        return out;
    }

    /**
     * 引用块里要显示的样子。
     *
     * <p>{@code data} 里给模型的仍是原始数值(它要拿去比大小、排序),
     * 但<b>用户看到的那一份必须带货币符号和千分位</b> —— 「1234567.89」和页面上的
     * 「¥1,234,567.89」是同一个数,可用户得自己在心里加逗号才能确认,
     * 而这个功能的全部意义就是让他不用怀疑。格式化走 {@link MetricExplainService},
     * 与页面同一份实现,于是「逐字一致」是真的逐字。</p>
     */
    private String display(String measureKey, BigDecimal v, String ccy) {
        if (v == null) return "—";
        return switch (measureKey) {
            case "share", "latestReturn", "cumReturn" -> fmt.pctUnits(v, 2);
            default -> fmt.money(ccy, v);
        };
    }

    /** 度量的中文名;目录里没有就退回 code(总比显示空白强) */
    private static String measureLabel(String mk) {
        LensRegistry.Measure md = LensRegistry.MEASURES.get(mk);
        return md == null ? mk : md.label();
    }
}
