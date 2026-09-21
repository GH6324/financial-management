package com.family.finance.service.expense;

import com.family.finance.domain.expense.ExpenseCategory;
import com.family.finance.domain.expense.ExpenseNature;
import com.family.finance.domain.period.Period;
import com.family.finance.repository.CashFlowMapper;
import com.family.finance.repository.ExpenseCategoryMapper;
import com.family.finance.repository.PeriodMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * v1.24 FR-620~625 · 「这个月为什么变了」—— 本期 vs 上期的归因瀑布。
 *
 * <h3>「上期」怎么取(这一版最容易写错的一处)</h3>
 *
 * <p>判据是 {@link PeriodMapper#previousOf} 那条:<b>period_start 严格小于锚期的那些期里,
 * period_start 最大的那一个</b>。v1.23 的双活跃账期让「上一个 OPEN 的前面那个」
 * 这种直觉写法失效 —— 补录期与新期可以同时开着。</p>
 *
 * <h3>上期是 TOTAL 模式时不画(FR-625)</h3>
 *
 * <p>上期只填了总额、没有分类,那么「上期餐饮花了多少」这个数<b>不存在</b>。
 * 拿 0 当它的值,瀑布上每个类目都会显示成「新增」,一整屏红条 ——
 * 用户会以为这个月突然多花了一倍。这是 PRD 点名的失败模式 F3。
 * 所以那种情况返回 {@link Waterfall#unavailable},页面出一行说明。</p>
 */
@Service
@RequiredArgsConstructor
public class ExpenseAttributionService {

    /** FR-621 · 瀑布上显示的变化条数,其余合并成「其他 N 项合计」 */
    public static final int TOP_N = 5;

    /** FR-622 · 金额差小于这个数的类目不单独成条 —— 一分两分的波动不是「变化」 */
    private static final BigDecimal NOISE = new BigDecimal("1");

    private static final String CONSUMPTION = "consumption";

    private final PeriodMapper periodMapper;
    private final CashFlowMapper cashFlowMapper;
    private final ExpenseCategoryMapper categoryMapper;
    private final ExpenseNatureService natureService;
    private final ExpenseLedgerService ledgerService;

    /**
     * 瀑布上的一根条。
     *
     * @param delta   本期 − 上期。正 = 多花了(红),负 = 少花了(绿)
     * @param pctText 变化率的显示文本;上期为 0 时是「新增」而不是 ∞
     * @param oneOff  这一条是不是一次性支出段(FR-623 · 斜纹填充 + 「不进常态月均」)
     */
    public record Bar(String label, BigDecimal from, BigDecimal to, BigDecimal delta,
                      String pctText, boolean oneOff, boolean merged, int mergedCount,
                      BigDecimal widthPct, List<Bar> members) {

        public boolean up() { return delta.signum() > 0; }

        Bar withWidth(BigDecimal w) {
            return new Bar(label, from, to, delta, pctText, oneOff, merged, mergedCount, w, members);
        }

        /**
         * 「其他 N 项合计」标签里的 N,必须等于真的被合并进来的成员数。
         *
         * <p>看着多余,但它防的是一类很具体的错:top-N 下标算错时标签写「其他 3 项」
         * 而 members 里躺着 5 个 —— 用户点开看到 5 行,而那个「3」是页面上唯一能核对的地方。</p>
         */
        public boolean countMatchesMembers() { return !merged || mergedCount == members.size(); }
    }

    /**
     * 归因瀑布的完整结果。
     *
     * @param available   false = 画不出来(上期不存在 / 上期是 TOTAL 模式)
     * @param reason      画不出来时给用户的一句话
     * @param yoyText     FR-624 · 同比;数据不足 13 期时是「攒够 13 期才有」而不是 0 或空
     */
    public record Waterfall(boolean available, String reason,
                            String fromLabel, String toLabel,
                            BigDecimal fromTotal, BigDecimal toTotal,
                            List<Bar> bars, String momText, String yoyText) {

        public static Waterfall unavailable(String reason) {
            return new Waterfall(false, reason, null, null,
                    BigDecimal.ZERO, BigDecimal.ZERO, List.of(), null, null);
        }

        public BigDecimal delta() { return toTotal.subtract(fromTotal); }
    }

    /** FR-620 · 算这一期相对上期的归因瀑布。 */
    public Waterfall waterfall(long familyId, Period anchor) {
        if (anchor == null || anchor.getId() == null) {
            return Waterfall.unavailable("还没有账期,攒一个月再看。");
        }
        Optional<Period> prevOpt = periodMapper.previousOf(familyId, anchor.getId());
        if (prevOpt.isEmpty()) {
            return Waterfall.unavailable("这是第一期,还没有可比的上期。");
        }
        Period prev = prevOpt.get();

        // FR-625 · 上期只填了总额 → 拆不开,不许拿 0 当各类目的值
        var prevExpense = ledgerService.byPeriod(familyId, prev.getId());
        if (prevExpense.source() == ExpenseLedgerService.PeriodExpense.Source.TOTAL) {
            return Waterfall.unavailable("上期只填了总额,拆不开 —— 这个月开始逐笔记,下期就能看了。");
        }

        Map<Long, ExpenseNature> natures = natureService.natureMap(familyId);
        Map<Long, String> names = categoryNames(familyId);

        // 一次查两期,两条键:分类 id(一次性的笔单独归到一个虚拟键上)
        Map<String, BigDecimal> from = new LinkedHashMap<>();
        Map<String, BigDecimal> to = new LinkedHashMap<>();
        BigDecimal fromTotal = BigDecimal.ZERO, toTotal = BigDecimal.ZERO;

        for (var r : cashFlowMapper.sumExpenseByPeriodCategoryOneOff(
                familyId, List.of(prev.getId(), anchor.getId()))) {
            if (!CONSUMPTION.equals(r.categoryCode())) continue;
            boolean isPrev = prev.getId().equals(r.periodId());
            BigDecimal v = nz(r.amountBase());
            // FR-623 · 一次性单独成段:它不属于任何一个类目的"涨跌",
            //   它就是"这个月多了一件事"。混进类目里会让那个类目看起来突然失控。
            boolean oneOff = natureService.natureOf(r.oneOff(), r.expenseCategoryId(), natures)
                    == ExpenseNature.ONE_OFF;
            String key = oneOff ? ONE_OFF_KEY : String.valueOf(r.expenseCategoryId());
            (isPrev ? from : to).merge(key, v, BigDecimal::add);
            if (isPrev) fromTotal = fromTotal.add(v); else toTotal = toTotal.add(v);
        }

        List<Bar> bars = buildBars(from, to, names);
        return new Waterfall(true, null,
                label(prev), label(anchor), fromTotal, toTotal, bars,
                momText(fromTotal, toTotal), yoyText(familyId, anchor, toTotal));
    }

    private static final String ONE_OFF_KEY = "__one_off__";

    /** FR-621/622 · 按 |差额| 降序取前 N 条,其余合并 */
    private List<Bar> buildBars(Map<String, BigDecimal> from, Map<String, BigDecimal> to,
                                Map<Long, String> names) {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        keys.addAll(from.keySet());
        keys.addAll(to.keySet());

        List<Bar> all = new ArrayList<>();
        for (String k : keys) {
            BigDecimal f = from.getOrDefault(k, BigDecimal.ZERO);
            BigDecimal t = to.getOrDefault(k, BigDecimal.ZERO);
            BigDecimal d = t.subtract(f);
            if (d.abs().compareTo(NOISE) < 0) continue;
            all.add(new Bar(labelOf(k, names), f, t, d, pctText(f, t),
                    ONE_OFF_KEY.equals(k), false, 0, BigDecimal.ZERO, List.of()));
        }
        all.sort((a, b) -> b.delta().abs().compareTo(a.delta().abs()));
        if (all.size() <= TOP_N) return withWidths(all);

        List<Bar> head = new ArrayList<>(all.subList(0, TOP_N));
        List<Bar> tail = all.subList(TOP_N, all.size());
        BigDecimal f = BigDecimal.ZERO, t = BigDecimal.ZERO;
        for (Bar b : tail) { f = f.add(b.from()); t = t.add(b.to()); }
        /* 【把成员带上】—— 只给一个合计数,用户没法知道是哪几项在动,
         * 而「其他」里藏着的往往正是他想找的那一笔。成员已经按 |差额| 排好序了。 */
        head.add(new Bar("其他 " + tail.size() + " 项合计", f, t, t.subtract(f),
                pctText(f, t), false, true, tail.size(), BigDecimal.ZERO, List.copyOf(tail)));
        return withWidths(head);
    }

    /**
     * 条宽 = |差额| ÷ 最大 |差额|。
     *
     * <p>算在这里而不是模板里 —— 模板不做算术是这个项目的纪律。
     * 下限由 CSS 的 {@code min-width:9px} 兜:没有下限时 ¥310 的差按比例算出来只有 2px,
     * 在图上退化成一根横线,而<b>小额差本来就该看得出方向</b>。</p>
     */
    private static List<Bar> withWidths(List<Bar> bars) {
        BigDecimal max = BigDecimal.ZERO;
        for (Bar b : bars) if (b.delta().abs().compareTo(max) > 0) max = b.delta().abs();
        if (max.signum() == 0) return bars;
        List<Bar> out = new ArrayList<>();
        for (Bar b : bars) {
            out.add(b.withWidth(b.delta().abs().multiply(BigDecimal.valueOf(100))
                    .divide(max, 1, RoundingMode.HALF_UP)));
        }
        return out;
    }

    private String labelOf(String key, Map<Long, String> names) {
        if (ONE_OFF_KEY.equals(key)) return "一次性支出";
        if ("null".equals(key)) return ExpenseCatQueryService.UNCLASSIFIED;
        try { return names.getOrDefault(Long.parseLong(key), ExpenseCatQueryService.UNCLASSIFIED); }
        catch (NumberFormatException e) { return ExpenseCatQueryService.UNCLASSIFIED; }
    }

    private Map<Long, String> categoryNames(long familyId) {
        Map<Long, ExpenseCategory> byId = new LinkedHashMap<>();
        for (ExpenseCategory c : categoryMapper.findByFamily(familyId)) byId.put(c.getId(), c);
        Map<Long, String> out = new LinkedHashMap<>();
        byId.forEach((id, c) -> {
            ExpenseCategory parent = c.getParentId() == null ? null : byId.get(c.getParentId());
            out.put(id, parent == null ? c.getName() : parent.getName() + " › " + c.getName());
        });
        return out;
    }

    /**
     * FR-622 · 变化率是<b>次</b>信息。
     *
     * <p>上期为 0 时显示「新增」而不是 ∞ 或 +100% —— 从 0 涨到任何数,
     * 百分比都是无意义的,而「新增」恰好是用户脑子里的那个词。</p>
     */
    private static String pctText(BigDecimal from, BigDecimal to) {
        if (from == null || from.signum() == 0) return to.signum() == 0 ? "—" : "新增";
        if (to.signum() == 0) return "归零";
        BigDecimal pct = to.subtract(from)
                .multiply(BigDecimal.valueOf(100))
                .divide(from.abs(), 0, RoundingMode.HALF_UP);
        return (pct.signum() > 0 ? "+" : "") + pct.toPlainString() + "%";
    }

    private static String momText(BigDecimal from, BigDecimal to) {
        return "环比 " + pctText(from, to);
    }

    /**
     * FR-624 · 同比 = 去年同月。
     *
     * <p>数据不足 13 期时显示「攒够 13 期才有」—— <b>不显示 0 也不显示空白</b>。
     * 承 v1.21 FR-519 的纪律:没数据 ≠ 没花钱。</p>
     */
    private String yoyText(long familyId, Period anchor, BigDecimal toTotal) {
        if (anchor.getPeriodStart() == null) return "攒够 13 期才有同比";
        java.time.LocalDate lastYear = anchor.getPeriodStart().minusYears(1);
        Period same = periodMapper.findAllByFamily(familyId).stream()
                .filter(p -> p.getPeriodStart() != null && p.getPeriodStart().equals(lastYear))
                .findFirst().orElse(null);
        if (same == null) return "攒够 13 期才有同比";
        var pe = ledgerService.byPeriod(familyId, same.getId());
        if (!pe.filled()) return "去年同月没有记录";
        return "同比 " + pctText(nz(pe.amountBase()), toTotal);
    }

    private static String label(Period p) {
        if (p == null || p.getPeriodStart() == null) return "—";
        return "%d-%02d".formatted(p.getPeriodStart().getYear(), p.getPeriodStart().getMonthValue());
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
