package com.family.finance.service.expense;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.22 · 支出可以是负数(退款冲正原样记)之后,<b>构成饼图</b>是唯一需要新规则的地方。
 *
 * <p>扇形没有负面积。三条路只有一条是诚实的:</p>
 * <ul>
 *   <li>✗ 取绝对值凑扇形 —— 把一笔 −499 的退款画成一笔 499 的消费,用户完全看不出来</li>
 *   <li>✗ 把负组藏掉并从总额里扣走 —— 「各组加起来 ≠ 总额」,对账时对不上</li>
 *   <li>✓ 负组<b>不进扇形,但进总额</b>,在图例下方单列一行说明</li>
 * </ul>
 *
 * <p>这些断言钉的就是第三条。它们<b>不碰数据库</b> —— 测的是 Composition 这个值对象的
 * 派生规则,那正是会被「顺手改一下」弄坏的部分。</p>
 */
class ExpenseMixNegativeGroupTest {

    private static ExpenseLedgerService.Slice slice(String label, String amt, String pct, boolean inChart) {
        return new ExpenseLedgerService.Slice(label, label, new BigDecimal(amt),
                new BigDecimal(pct), 1, inChart);
    }

    private static ExpenseLedgerService.Composition comp(List<ExpenseLedgerService.Slice> slices, String total) {
        return new ExpenseLedgerService.Composition(
                ExpenseLedgerService.Dim.CATEGORY, slices, new BigDecimal(total), List.of());
    }

    @Test
    @DisplayName("负组不进饼图,但仍然在明细里")
    void negativeGroupExcludedFromChartButKept() {
        var c = comp(List.of(
                slice("餐饮美食", "800.00", "100.0", true),
                slice("数码电器", "-410.70", "0.0", false)), "389.30");

        assertThat(c.chartSlices())
                .as("饼图只拿到正组 —— 负值传给 Chart.js 会画出一个比例完全错的图")
                .extracting(ExpenseLedgerService.Slice::label)
                .containsExactly("餐饮美食");

        assertThat(c.negativeSlices())
                .as("负组要能被页面单独列出来说明,不能悄悄消失")
                .extracting(ExpenseLedgerService.Slice::label)
                .containsExactly("数码电器");

        assertThat(c.slices())
                .as("明细列表仍然是全量 —— 用户要能看到那一组的金额并点开逐笔")
                .hasSize(2);
    }

    @Test
    @DisplayName("各组金额之和 = 总额(负组也算进去)")
    void groupsSumToTotal() {
        var c = comp(List.of(
                slice("餐饮美食", "800.00", "100.0", true),
                slice("数码电器", "-410.70", "0.0", false)), "389.30");

        BigDecimal sum = c.slices().stream()
                .map(ExpenseLedgerService.Slice::amountBase)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(sum)
                .as("把负组从总额里也扣掉的话,「各组加起来 ≠ 总额」,用户对账时对不上")
                .isEqualByComparingTo(c.totalBase());
    }

    @Test
    @DisplayName("总额为负时 hasData 仍然是 true —— 不能整段隐藏")
    void negativeTotalStillHasData() {
        var c = comp(List.of(slice("数码电器", "-410.70", "0.0", false)), "-410.70");

        assertThat(c.hasData())
                .as("""
                    v1.22 之前这里判的是 totalBase.signum() > 0。
                    某个月退款多于消费 → 总额 ≤ 0 → 整个「支出构成」段被判成「没数据」直接隐藏,
                    不报错、不解释,用户看到的是一片空白 —— 而那个月明明有几十笔支出。
                    0 和负数都是合法且有含义的值,不是「没有」。""")
                .isTrue();
    }

    @Test
    @DisplayName("一笔都没有时 hasData 才是 false")
    void emptyHasNoData() {
        assertThat(comp(List.of(), "0.00").hasData())
                .as("「有没有数据」看的是有没有行")
                .isFalse();
    }

    @Test
    @DisplayName("全是正组时,负组列表为空、饼图拿到全部")
    void allPositiveUnchanged() {
        var c = comp(List.of(
                slice("餐饮美食", "800.00", "66.7", true),
                slice("交通出行", "400.00", "33.3", true)), "1200.00");

        assertThat(c.negativeSlices()).isEmpty();
        assertThat(c.chartSlices()).hasSize(2);
        assertThat(c.hasData()).isTrue();
    }
}
