package com.family.finance.service.expense;

import com.family.finance.domain.period.Period;
import com.family.finance.repository.PeriodMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.21 · 支出报表(分类填报口径)的读模型。
 *
 * <p>只读 {@code expense_split},<b>不碰家庭支出口径</b> —— 那条路仍然只走
 * {@code ExpenseLedgerService}(护栏 {@code v1210-LEDGER-UNTOUCHED} 钉着它的 diff 为空)。</p>
 *
 * <h3>「没拆分」不是 0</h3>
 *
 * <p>趋势图上,某一期没有任何分类数据时<b>不能画成 0</b> —— 那会显示成一段假的「支出暴跌」。
 * 这里把它标成 {@code unsplit},由模板画成斜纹整块,高度取该期的<b>月度总额</b>
 * (那个数是有的,只是没拆)。</p>
 */
@Service
@RequiredArgsConstructor
public class ExpenseMixQueryService {

    /** 趋势图的绘图高度上限(px)· 与模板里的容器高度一致 */
    private static final int MAX_PX = 150;

    private final ExpenseSplitService splitService;
    private final PeriodMapper periodMapper;
    private final com.family.finance.repository.PeriodMemberCashflowMapper pmcMapper;

    /** 一期在趋势图上的样子 */
    public record TrendCol(String label, String shortLabel, boolean unsplit,
                           int pixels, List<Seg> segments) {
        public record Seg(String name, BigDecimal amount, int pixels) {}
    }

    /** 某一期的按大类聚合(直接复用 ExpenseSplitService.rollup) */
    public List<ExpenseSplitService.TopRollup> period(long familyId, long periodId) {
        return splitService.rollup(familyId, periodId);
    }

    /**
     * 本自然年至今的按大类累计。
     *
     * <p><b>只算到锚期为止</b> —— 报表整体锚在最新已关账期(项目惯例),
     * 如果这里把进行中的那一期也算进来,「年度累计」就不等于「趋势各柱之和」了。
     * 同一页上两个数对不上,用户会以为哪个算错了(而两个都"对",只是口径不同 ——
     * 这正是最难解释的一类不一致)。</p>
     */
    public List<ExpenseSplitService.TopRollup> year(long familyId, int year, Period anchor) {
        Map<String, BigDecimal> acc = new LinkedHashMap<>();
        for (Period p : periodMapper.findAllByFamily(familyId)) {
            if (p.getPeriodStart() == null || p.getPeriodStart().getYear() != year) continue;
            if (anchor != null && anchor.getPeriodStart() != null
                    && p.getPeriodStart().isAfter(anchor.getPeriodStart())) continue;
            for (var r : splitService.rollup(familyId, p.getId())) {
                acc.merge(r.name(), r.total(), BigDecimal::add);
            }
        }
        List<ExpenseSplitService.TopRollup> out = new ArrayList<>();
        acc.forEach((name, total) -> out.add(
                new ExpenseSplitService.TopRollup(0L, name, total, List.of())));
        out.sort((a, b) -> b.total().compareTo(a.total()));
        return out;
    }

    /**
     * 近 N 期趋势。
     *
     * <p>没有分类数据的期标 {@code unsplit} 并用<b>月度总额</b>定高度 ——
     * 画成 0 就是撒谎(FR-519)。</p>
     */
    public List<TrendCol> trend(long familyId, Period anchor, int n) {
        List<Period> periods = periodMapper.findAllByFamily(familyId).stream()
                .filter(p -> p.getPeriodStart() != null
                        && !p.getPeriodStart().isAfter(anchor.getPeriodStart()))
                .sorted((a, b) -> a.getPeriodStart().compareTo(b.getPeriodStart()))
                .toList();
        if (periods.size() > n) periods = periods.subList(periods.size() - n, periods.size());

        // 先算每期总额,取最大值定标尺 —— 各期高度必须可比
        List<BigDecimal> totals = new ArrayList<>();
        List<List<ExpenseSplitService.TopRollup>> rolls = new ArrayList<>();
        BigDecimal max = BigDecimal.ZERO;
        for (Period p : periods) {
            var roll = splitService.rollup(familyId, p.getId());
            rolls.add(roll);
            BigDecimal t = BigDecimal.ZERO;
            for (var r : roll) t = t.add(r.total());
            if (t.signum() == 0) t = monthlyTotal(familyId, p.getId());   // 没拆分 → 用月度总额
            totals.add(t);
            if (t.compareTo(max) > 0) max = t;
        }
        if (max.signum() == 0) max = BigDecimal.ONE;

        List<TrendCol> out = new ArrayList<>();
        for (int i = 0; i < periods.size(); i++) {
            Period p = periods.get(i);
            BigDecimal t = totals.get(i);
            var roll = rolls.get(i);
            String label = p.getPeriodStart().toString().substring(0, 7);
            String shortLabel = String.format("%02d", p.getPeriodStart().getMonthValue());
            int px = px(t, max);
            if (roll.isEmpty()) {
                out.add(new TrendCol(label + " · 未拆分", shortLabel, true, px, List.of()));
                continue;
            }
            List<TrendCol.Seg> segs = new ArrayList<>();
            for (var r : roll) segs.add(new TrendCol.Seg(r.name(), r.total(), px(r.total(), max)));
            out.add(new TrendCol(label, shortLabel, false, px, segs));
        }
        return out;
    }

    /** 该期的家庭月度总额(所有成员的 PMC 支出之和)—— 只用来给「未拆分」柱定高度 */
    private BigDecimal monthlyTotal(long familyId, long periodId) {
        BigDecimal t = BigDecimal.ZERO;
        for (var row : pmcMapper.findByPeriod(periodId)) {
            if (row.getTotalExpenseInput() != null) t = t.add(row.getTotalExpenseInput());
        }
        return t;
    }

    private static int px(BigDecimal v, BigDecimal max) {
        if (v == null || v.signum() <= 0) return 0;
        int p = v.multiply(BigDecimal.valueOf(MAX_PX))
                 .divide(max, 0, RoundingMode.HALF_UP).intValue();
        return Math.max(p, 1);   // 有钱就至少 1px —— 小类目也得看得见
    }
}
