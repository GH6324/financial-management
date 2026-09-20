package com.family.finance.service.expense;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.24 FR-661~668 · 支出章节的<b>组装层</b>。
 *
 * <h3>硬约束:这个类不许注入任何 Mapper</h3>
 *
 * <p>它的输入全部是既有口径服务的输出,输出是模板要的 view record。
 * 一旦它能自己查 {@code cash_flow},就会出现<b>第三条求和路径</b> ——
 * 而这一版做的所有事情(把过滤块提成常量、删掉分叉的第二层查询)都是为了
 * 让求和口径只有一套。护栏 {@code v1240-NO-THIRD-SUM} 扫这个类的字段。</p>
 *
 * <p>它负责的是「怎么显示」:top-N 归并、两层差额、三种数据形态的判定、配色。
 * 这些都是<b>纯函数</b>,单测可以穷举。</p>
 */
@Service
@RequiredArgsConstructor
public class ExpenseSectionViewService {

    /** FR-665 · 累计占比到这里为止 */
    private static final BigDecimal CUM_TARGET = new BigDecimal("80");
    /** FR-665 · 片数夹在 [3, 6] */
    private static final int MIN_SLICES = 3;
    private static final int MAX_SLICES = 6;

    /**
     * 饼图上的一片。
     *
     * @param merged  是不是「其他 N 项」那一片
     * @param members 被合并进来的成员(「其他」片可点开看全量)
     */
    public record PieSlice(String key, String label, BigDecimal amountBase, BigDecimal pct,
                           String color, boolean merged, boolean unclassified,
                           List<ExpenseLedgerService.Slice> members) {

        /**
         * 「其他 N 项」这个标签里的 N,必须等于真的被合并进来的成员数。
         *
         * <p>看着多余,但它防的是一类很具体的错:top-N 的下标算错时,
         * 标签上写「其他 3 项」而 members 里躺着 5 个 —— 点开看到 5 行,
         * 而那个「3」是页面上唯一能核对的地方。单测直接断言它。</p>
         */
        public boolean mergedCountMatchesLabel() {
            if (!merged) return true;
            return label.equals("其他 " + members.size() + " 项");
        }
    }

    /**
     * FR-665 · 饼图只画 top-N,N 由累计占比决定。
     *
     * <h3>三条判据,顺序不能换</h3>
     *
     * <ol>
     *   <li><b>「未分类」永远单独成片,不进「其他」。</b>先把它拎出来 ——
     *       未分类是<b>要用户去处理的事</b>,合进「其他」就等于把它藏起来了
     *       (L13 硬约束③:不许把未分类偷偷丢掉)。</li>
     *   <li>其余按金额降序累加,取到<b>累计 ≥ 80%</b> 为止,片数夹在 [3, 6]:
     *       不足 3 个就到 80% 仍画 3 个(两片的饼图没有信息量);
     *       6 个还不到 80% 就停在 6(再多就分不出颜色了)。</li>
     *   <li>类目总数 ≤ 6 时<b>不合并,全画</b> —— 「其他 1 项」是纯粹的噪音。</li>
     * </ol>
     */
    public List<PieSlice> topSlices(List<ExpenseLedgerService.Slice> slices) {
        if (slices == null || slices.isEmpty()) return List.of();

        BigDecimal total = BigDecimal.ZERO;
        for (var s : slices) total = total.add(nz(s.amountBase()));

        List<ExpenseLedgerService.Slice> unclassified = new ArrayList<>();
        List<ExpenseLedgerService.Slice> rest = new ArrayList<>();
        for (var s : slices) {
            if (ExpenseCatQueryService.UNCLASSIFIED.equals(s.label())) unclassified.add(s);
            else rest.add(s);
        }
        rest.sort((a, b) -> nz(b.amountBase()).compareTo(nz(a.amountBase())));

        int n = pickN(rest, total);

        List<PieSlice> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, rest.size()); i++) {
            var s = rest.get(i);
            out.add(new PieSlice(s.groupKey(), s.label(), nz(s.amountBase()), pct(s.amountBase(), total),
                    ExpensePalette.categoryAt(i), false, false, List.of()));
        }
        if (rest.size() > n) {
            List<ExpenseLedgerService.Slice> tail = rest.subList(n, rest.size());
            BigDecimal sum = BigDecimal.ZERO;
            for (var s : tail) sum = sum.add(nz(s.amountBase()));
            out.add(new PieSlice("__other__", "其他 " + tail.size() + " 项", sum, pct(sum, total),
                    ExpensePalette.OTHER, true, false, List.copyOf(tail)));
        }
        // 未分类放最后,永远单独一片
        for (var s : unclassified) {
            out.add(new PieSlice(s.groupKey(), s.label(), nz(s.amountBase()), pct(s.amountBase(), total),
                    ExpensePalette.UNCLASSIFIED, false, true, List.of()));
        }
        return out;
    }

    /** 累计到 80% 需要几片,夹在 [3,6];总数 ≤ 6 则全画 */
    private int pickN(List<ExpenseLedgerService.Slice> sorted, BigDecimal total) {
        if (sorted.size() <= MAX_SLICES) return sorted.size();
        if (total.signum() == 0) return Math.min(MIN_SLICES, sorted.size());
        BigDecimal cum = BigDecimal.ZERO;
        int n = 0;
        for (var s : sorted) {
            cum = cum.add(nz(s.amountBase()));
            n++;
            if (pct(cum, total).compareTo(CUM_TARGET) >= 0) break;
            if (n >= MAX_SLICES) break;
        }
        return Math.min(MAX_SLICES, Math.max(MIN_SLICES, n));
    }

    /**
     * FR-663 · 两层之间的差额。
     *
     * @param layer1Consumption 第一层「日常开支」那一片的金额
     * @param layer2Total       第二层(消费分类)的合计
     *
     * <p>口径对齐之后差额<b>只剩一个来源</b>:既是还贷 / 利息 / 给亲属、
     * 又带了消费分类的那些笔(它们进第二层的分类里,但第一层归在别的片)。
     * 这个差额是<b>可解释的业务事实</b>,所以如实显示;
     * 而对齐之前那四处(归档账户 / 现金调整 / 不换汇 / 无家庭隔离)是内部不一致,
     * 不该让用户在「差额说明」里看到。</p>
     */
    public record LayerDiff(BigDecimal layer1Consumption, BigDecimal layer2Total,
                            BigDecimal diff, boolean matched) {

        public String explain() {
            if (matched) return null;
            return diff.signum() > 0
                    ? "第一层多出 " + diff.abs().toPlainString() + " —— 有几笔日常开支还没归类到消费分类里。"
                    : "第二层多出 " + diff.abs().toPlainString()
                      + " —— 有几笔还贷 / 利息 / 给亲属的钱被挂了消费分类,它们不算「日常开支」。";
        }
    }

    public LayerDiff layerDiff(BigDecimal layer1Consumption, BigDecimal layer2Total) {
        BigDecimal a = nz(layer1Consumption), b = nz(layer2Total);
        BigDecimal d = a.subtract(b);
        // 一分钱以内算对上 —— 两层各自四舍五入到分,差一分是舍入不是业务事实
        return new LayerDiff(a, b, d, d.abs().compareTo(new BigDecimal("0.01")) <= 0);
    }

    /**
     * FR-673 · 三种数据形态。
     *
     * <p>不能只说「自动判断」—— 每种形态页面长什么样必须是明确的,
     * 否则「看不到新块」这件事既可能是设计、也可能是 bug,没人分得清。</p>
     */
    public enum Shape {
        /** 形态 1 · TOTAL 模式(只填月度总额)→ 新块一个都不出现,章节与上一版逐字一致 */
        TOTAL_ONLY,
        /** 形态 2 · 有逐笔、无分类 → 只显示合并后的构成块;三分与瀑布不渲染 */
        ITEMIZED_NO_CATEGORY,
        /** 形态 3 · 有逐笔、有分类 → 全开 */
        FULL,
        /** FR-674 开关关掉 → 与形态 1 表现一致,但原因不同(要能区分开才好排查) */
        DISABLED
    }

    /**
     * 判形态。
     *
     * @param enabled        FR-674 开关
     * @param itemized       这个窗口里有没有逐笔数据
     * @param classifiedRows 带消费分类的笔数
     */
    public Shape shapeOf(boolean enabled, boolean itemized, int classifiedRows) {
        if (!enabled) return Shape.DISABLED;
        if (!itemized) return Shape.TOTAL_ONLY;
        return classifiedRows > 0 ? Shape.FULL : Shape.ITEMIZED_NO_CATEGORY;
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) return BigDecimal.ZERO;
        return nz(part).multiply(BigDecimal.valueOf(100))
                .divide(whole.abs(), 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
