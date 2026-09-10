package com.family.finance.service.expense;

import com.family.finance.domain.expense.ExpenseCategory;
import com.family.finance.repository.ExpenseCategoryMapper;
import com.family.finance.domain.period.Period;
import com.family.finance.repository.ExpenseFlowMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.21(第 2 稿)· 报表侧「钱花在哪了」的取数。
 *
 * <h3>只有一个数据源</h3>
 *
 * <p>数全部来自 {@code cash_flow}(kind=EXPENSE 且 category_code='consumption'),按
 * {@code expense_category_id} 聚合。第 1 稿有过一张 {@code expense_split} 汇总表,
 * 于是「同一个月的支出构成」有两个来源(逐笔 / 手填汇总),报表得说清这个月的数是哪来的 ——
 * 而两个来源迟早会对不上。第 2 稿只有一个来源,这个问题不存在。</p>
 *
 * <h3>与家庭支出口径的关系</h3>
 *
 * <p>这里算的是<b>消费构成</b>,不是家庭支出总额。总额永远归
 * {@link ExpenseLedgerService} 管(v1.8 的教训:多一个测量源就会多一个 89% 的偏差)。
 * 两者<b>本来就不相等</b>:还贷 / 利息支出 / 转账给亲属进总额但不进构成。
 * 页面上必须把这句说出来,否则用户会拿构成合计去对账本总额,然后以为我们算错了。</p>
 *
 * <h3>「未分类」是一等公民</h3>
 *
 * <p>v1.21 之前的历史流水 {@code expense_category_id} 为 null。它们<b>不能被过滤掉</b> ——
 * 那样构成合计会莫名其妙地小于消费总额。如实显示成「未分类」并给出笔数,
 * 用户想整理就点进去改,不想整理也不影响任何金额。</p>
 */
@Service
@RequiredArgsConstructor
public class ExpenseCatQueryService {

    private final ExpenseFlowMapper flowMapper;
    private final ExpenseCategoryMapper categoryMapper;
    private final PeriodMapper periodMapper;
    private final PeriodMemberCashflowMapper pmcMapper;

    /** 未分类那一行在页面上的固定叫法 —— 只有一处,免得几个模板各写各的 */
    public static final String UNCLASSIFIED = "未分类";

    /**
     * 报表里的一行。
     *
     * @param categoryId null = 未分类
     * @param leaves     该大类下的细类构成(下钻用)· 记在大类自己身上的那部分作为一条 leaf 如实出现
     */
    public record CatRow(Long categoryId, String name, BigDecimal total, int rowCount,
                         List<Leaf> leaves) {}

    public record Leaf(Long categoryId, String name, BigDecimal amount, int rowCount) {}

    /**
     * 一期的构成,<b>按大类</b>汇总(FR-519a),大类内保留细类构成供下钻。
     *
     * <p>金额降序 —— 用户想知道的是「钱主要花在哪」,不是「类目按什么顺序建的」。</p>
     */
    public List<CatRow> period(long familyId, long periodId) {
        List<ExpenseFlowMapper.CatSum> sums = flowMapper.sumByCategory(periodId);
        if (sums.isEmpty()) return List.of();

        Map<Long, ExpenseCategory> byId = new LinkedHashMap<>();
        for (ExpenseCategory c : categoryMapper.findByFamily(familyId)) byId.put(c.getId(), c);

        // 大类 id → 累计;同时记住每个大类下的细类构成
        Map<Long, BigDecimal> topTotal = new LinkedHashMap<>();
        Map<Long, Integer> topRows = new LinkedHashMap<>();
        Map<Long, List<Leaf>> topLeaves = new LinkedHashMap<>();

        for (var s : sums) {
            BigDecimal amt = nz(s.amount());
            ExpenseCategory c = s.categoryId() == null ? null : byId.get(s.categoryId());
            /* 分类被删掉之后还挂着旧 id 的流水:理论上删除时已经搬走了,
             * 但导入并发 / 手工改库都可能留下孤儿。当「未分类」处理而不是丢掉 —— 钱不能凭空消失。 */
            Long topId = (c == null) ? null : (c.isTopLevel() ? c.getId() : c.getParentId());
            topTotal.merge(topId, amt, BigDecimal::add);
            topRows.merge(topId, s.rowCount(), Integer::sum);
            topLeaves.computeIfAbsent(topId, k -> new ArrayList<>())
                    .add(new Leaf(s.categoryId(), leafName(c, byId), amt, s.rowCount()));
        }

        List<CatRow> out = new ArrayList<>();
        for (var e : topTotal.entrySet()) {
            ExpenseCategory top = e.getKey() == null ? null : byId.get(e.getKey());
            List<Leaf> leaves = topLeaves.getOrDefault(e.getKey(), List.of());
            leaves = leaves.stream()
                    .sorted((x, y) -> nz(y.amount()).compareTo(nz(x.amount()))).toList();
            out.add(new CatRow(e.getKey(), top == null ? UNCLASSIFIED : top.getName(),
                    e.getValue(), topRows.getOrDefault(e.getKey(), 0), leaves));
        }
        out.sort((a, b) -> nz(b.total()).compareTo(nz(a.total())));
        return out;
    }

    /**
     * 细类在报表里的叫法。
     *
     * <p>钱记在<b>大类自己</b>身上时,如实显示成大类名 —— 第 1 稿在这里叫「未细分」,
     * 那是因为当时「深度」是个设置项、大类上有钱属于例外状态。第 2 稿里
     * 「记在大类上」是完全正常的选择(点大类就提交),不需要一个特殊称呼。</p>
     */
    private String leafName(ExpenseCategory c, Map<Long, ExpenseCategory> byId) {
        if (c == null) return UNCLASSIFIED;
        return c.getName();
    }

    /**
     * 本自然年至今的按大类累计。
     *
     * <p><b>必须锚在同一批账期上</b>:第 1 稿这里算的是整个自然年(含进行中的期),
     * 而趋势和本期都锚在最新已关账期 —— 于是同一页上「年度累计 ≠ Σ趋势」,
     * 两个数都「对」,只是口径不同。那是最难向用户解释的一类不一致。</p>
     */
    public List<CatRow> year(long familyId, int year, Period anchor) {
        List<Long> ids = new ArrayList<>();
        for (Period p : periodMapper.findAllByFamily(familyId)) {
            if (p.getPeriodStart() == null || p.getPeriodStart().getYear() != year) continue;
            if (anchor != null && anchor.getPeriodStart() != null
                    && p.getPeriodStart().isAfter(anchor.getPeriodStart())) continue;
            ids.add(p.getId());
        }
        if (ids.isEmpty()) return List.of();

        Map<Long, ExpenseCategory> byId = new LinkedHashMap<>();
        for (ExpenseCategory c : categoryMapper.findByFamily(familyId)) byId.put(c.getId(), c);
        Map<Long, BigDecimal> topTotal = new LinkedHashMap<>();
        for (var s : flowMapper.sumByPeriodAndCategory(ids)) {
            ExpenseCategory c = s.categoryId() == null ? null : byId.get(s.categoryId());
            Long topId = (c == null) ? null : (c.isTopLevel() ? c.getId() : c.getParentId());
            topTotal.merge(topId, nz(s.amount()), BigDecimal::add);
        }
        List<CatRow> out = new ArrayList<>();
        for (var e : topTotal.entrySet()) {
            ExpenseCategory top = e.getKey() == null ? null : byId.get(e.getKey());
            out.add(new CatRow(e.getKey(), top == null ? UNCLASSIFIED : top.getName(),
                    e.getValue(), 0, List.of()));
        }
        out.sort((a, b) -> nz(b.total()).compareTo(nz(a.total())));
        return out;
    }

    /** 趋势图上的一根柱 */
    public record TrendCol(long periodId, String label, String shortLabel, boolean unclassifiedOnly,
                           boolean noData, int pixels, List<Seg> segments) {
        public record Seg(Long categoryId, String name, BigDecimal amount, int pixels) {}
    }

    /**
     * 近 N 期趋势。
     *
     * <p><b>没有分类数据的期不能画成 0</b>(FR-519)—— 用家庭月度总额撑高度并画成斜纹块。
     * 画成 0 的话趋势图上会出现一段假的「支出暴跌」,而那个月用户明明花了钱。</p>
     *
     * <p>一次把所有期的数查完 —— 在循环里逐期查是标准的 N+1(联动链)。</p>
     */
    public List<TrendCol> trend(long familyId, Period anchor, int n) {
        List<Period> periods = periodMapper.findAllByFamily(familyId).stream()
                .filter(p -> p.getPeriodStart() != null
                        && !p.getPeriodStart().isAfter(anchor.getPeriodStart()))
                .sorted((a, b) -> a.getPeriodStart().compareTo(b.getPeriodStart()))
                .toList();
        if (periods.isEmpty()) return List.of();
        if (periods.size() > n) periods = periods.subList(periods.size() - n, periods.size());

        List<Long> ids = periods.stream().map(Period::getId).toList();
        Map<Long, ExpenseCategory> byId = new LinkedHashMap<>();
        for (ExpenseCategory c : categoryMapper.findByFamily(familyId)) byId.put(c.getId(), c);

        Map<Long, Map<Long, BigDecimal>> byPeriod = new LinkedHashMap<>();
        for (var s : flowMapper.sumByPeriodAndCategory(ids)) {
            ExpenseCategory c = s.categoryId() == null ? null : byId.get(s.categoryId());
            Long topId = (c == null) ? null : (c.isTopLevel() ? c.getId() : c.getParentId());
            byPeriod.computeIfAbsent(s.periodId(), k -> new LinkedHashMap<>())
                    .merge(topId, nz(s.amount()), BigDecimal::add);
        }

        // 先定标尺 —— 各期高度必须可比,不能每根柱各自归一化
        Map<Long, BigDecimal> totals = new LinkedHashMap<>();
        BigDecimal max = BigDecimal.ZERO;
        for (Period p : periods) {
            Map<Long, BigDecimal> m = byPeriod.get(p.getId());
            BigDecimal t = BigDecimal.ZERO;
            if (m != null) for (BigDecimal v : m.values()) t = t.add(v);
            if (t.signum() == 0) t = monthlyTotal(p.getId());
            totals.put(p.getId(), t);
            if (t.compareTo(max) > 0) max = t;
        }
        if (max.signum() == 0) max = BigDecimal.ONE;

        List<TrendCol> out = new ArrayList<>();
        for (Period p : periods) {
            BigDecimal t = totals.get(p.getId());
            Map<Long, BigDecimal> m = byPeriod.get(p.getId());
            String label = p.getPeriodStart().toString().substring(0, 7);
            String shortLabel = String.format("%02d", p.getPeriodStart().getMonthValue());
            int px = px(t, max);
            if (m == null || m.isEmpty()) {
                out.add(new TrendCol(p.getId(), label + " · 没有分类数据", shortLabel,
                        false, true, px, List.of()));
                continue;
            }
            List<TrendCol.Seg> segs = new ArrayList<>();
            boolean onlyUnclassified = true;
            List<Map.Entry<Long, BigDecimal>> es = new ArrayList<>(m.entrySet());
            es.sort((x, y) -> nz(y.getValue()).compareTo(nz(x.getValue())));
            for (var e : es) {
                ExpenseCategory top = e.getKey() == null ? null : byId.get(e.getKey());
                if (e.getKey() != null) onlyUnclassified = false;
                segs.add(new TrendCol.Seg(e.getKey(), top == null ? UNCLASSIFIED : top.getName(),
                        e.getValue(), px(e.getValue(), max)));
            }
            out.add(new TrendCol(p.getId(), label, shortLabel, onlyUnclassified, false, px, segs));
        }
        return out;
    }

    /** 该期的家庭月度总额 —— 只用来给「没有分类数据」的柱子定高度,不参与任何口径 */
    private BigDecimal monthlyTotal(long periodId) {
        BigDecimal t = BigDecimal.ZERO;
        for (var row : pmcMapper.findByPeriod(periodId)) {
            if (row.getTotalExpenseInput() != null) t = t.add(row.getTotalExpenseInput());
        }
        return t;
    }

    /** 柱高。最小 2px —— 一笔很小的支出也该看得见,否则那一期看起来像空的。 */
    private static int px(BigDecimal v, BigDecimal max) {
        if (v == null || v.signum() <= 0) return 0;
        int p = v.multiply(BigDecimal.valueOf(150)).divide(max, 0, RoundingMode.HALF_UP).intValue();
        return Math.max(2, p);
    }

    /** 下钻:某一期某个分类的那些笔(FR-570) */
    public List<ExpenseFlowMapper.FlowRow> drillDown(long periodId, Long categoryId) {
        return flowMapper.drillDown(periodId, categoryId);
    }

    /** 这个家有没有任何一笔带分类的支出 —— 报表据此决定要不要渲染整块 */
    public boolean hasAny(long familyId, List<Long> periodIds) {
        if (periodIds == null || periodIds.isEmpty()) return false;
        for (var s : flowMapper.sumByPeriodAndCategory(periodIds)) {
            if (s.categoryId() != null) return true;
        }
        return false;
    }

    public static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) return BigDecimal.ZERO;
        return nz(part).multiply(BigDecimal.valueOf(100))
                .divide(whole, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
