package com.family.finance.service.expense;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.24 FR-663/665/673 · 组装层的三条判据,穷举。
 *
 * <p>这些都是纯函数,所以能穷举 —— 这正是选型八把 top-N 放服务端而不是
 * 模板 / 前端 JS 的原因:放到 JS 里之后,「未分类永远单独成片」这类判据
 * 就没有任何机器能守了。</p>
 */
class ExpenseSectionViewTest {

    private final ExpenseSectionViewService svc = new ExpenseSectionViewService();

    private ExpenseLedgerService.Slice sl(String label, String amt) {
        return new ExpenseLedgerService.Slice(label, label, new BigDecimal(amt),
                BigDecimal.ZERO, 1, true);
    }

    // ───────────────────────── FR-665 · top-N ─────────────────────────

    @Test
    @DisplayName("类目 ≤ 6 个 → 全画,不合并(「其他 1 项」是纯噪音)")
    void sixOrFewerAreAllDrawn() {
        List<ExpenseLedgerService.Slice> in = new ArrayList<>();
        for (int i = 0; i < 6; i++) in.add(sl("c" + i, String.valueOf(100 - i)));
        var out = svc.topSlices(in);
        assertThat(out).hasSize(6);
        assertThat(out).noneMatch(ExpenseSectionViewService.PieSlice::merged);
    }

    @Test
    @DisplayName("累计到 80% 就停 —— 头部两片已占 85% 时仍画满下限 3 片")
    void stopsAtCumulative80ButKeepsMinimumThree() {
        var out = svc.topSlices(List.of(
                sl("a", "500"), sl("b", "350"), sl("c", "60"),
                sl("d", "40"), sl("e", "30"), sl("f", "20"), sl("g", "10")));
        // a+b = 850/1010 = 84% → 2 片就够,但下限是 3
        assertThat(out.stream().filter(s -> !s.merged()).count()).isEqualTo(3);
        assertThat(out.getLast().merged()).isTrue();
        assertThat(out.getLast().label()).isEqualTo("其他 4 项");
    }

    @Test
    @DisplayName("6 片还不到 80% → 停在 6,不无限往下画")
    void capsAtSixEvenIfBelow80() {
        List<ExpenseLedgerService.Slice> in = new ArrayList<>();
        for (int i = 0; i < 20; i++) in.add(sl("c" + i, "50"));   // 均分,6 片只有 30%
        var out = svc.topSlices(in);
        assertThat(out.stream().filter(s -> !s.merged()).count()).isEqualTo(6);
        assertThat(out).hasSize(7);
    }

    /**
     * L13 硬约束③:不许把未分类偷偷丢掉。
     * 合进「其他」就等于把「要用户去处理的事」藏起来了。
     */
    @Test
    @DisplayName("FR-665 · 「未分类」永远单独成片,哪怕它金额最小")
    void unclassifiedNeverMergesIntoOther() {
        List<ExpenseLedgerService.Slice> in = new ArrayList<>();
        for (int i = 0; i < 10; i++) in.add(sl("c" + i, String.valueOf(1000 - i * 10)));
        in.add(sl(ExpenseCatQueryService.UNCLASSIFIED, "1"));     // 最小的一片
        var out = svc.topSlices(in);
        var unc = out.stream().filter(ExpenseSectionViewService.PieSlice::unclassified).toList();
        assertThat(unc).as("未分类必须自己一片").hasSize(1);
        assertThat(unc.getFirst().color()).isEqualTo(ExpensePalette.UNCLASSIFIED);
        assertThat(out.stream().filter(ExpenseSectionViewService.PieSlice::merged))
                .as("「其他」片里不许含未分类").allMatch(s -> s.members().stream()
                        .noneMatch(m -> ExpenseCatQueryService.UNCLASSIFIED.equals(m.label())));
    }

    @Test
    @DisplayName("「其他」片带着成员清单 —— 它要能点开看全量")
    void otherSliceCarriesItsMembers() {
        List<ExpenseLedgerService.Slice> in = new ArrayList<>();
        for (int i = 0; i < 10; i++) in.add(sl("c" + i, String.valueOf(100 - i)));
        var merged = svc.topSlices(in).stream()
                .filter(ExpenseSectionViewService.PieSlice::merged).findFirst().orElseThrow();
        assertThat(merged.members()).isNotEmpty();
        assertThat(merged.mergedCountMatchesLabel()).isTrue();
    }

    // ───────────────────────── FR-663 · 两层差额 ─────────────────────────

    @Test
    @DisplayName("两层相等(差一分以内)→ 不出差额说明")
    void layersMatchWithinOneCent() {
        var d = svc.layerDiff(new BigDecimal("1000.00"), new BigDecimal("1000.00"));
        assertThat(d.matched()).isTrue();
        assertThat(d.explain()).isNull();
        assertThat(svc.layerDiff(new BigDecimal("1000.00"), new BigDecimal("999.99")).matched())
                .as("差一分是舍入不是业务事实").isTrue();
    }

    @Test
    @DisplayName("第二层多出 → 说清是「还贷/利息挂了消费分类」,不许悄悄对不上")
    void layer2LargerExplainsWhy() {
        var d = svc.layerDiff(new BigDecimal("900"), new BigDecimal("1000"));
        assertThat(d.matched()).isFalse();
        assertThat(d.explain()).contains("第二层多出").contains("还贷");
    }

    // ───────────── 负档:退款多于消费的那一档(beta 实测撞到) ─────────────

    /**
     * 某一档退款多于消费时,三分条<b>不能</b>用算术占比当宽度。
     *
     * <p>beta 实测:一次性那一档因为退款冲正成了 −3.6%,弹性算出 103.2%
     * —— 三个数学上都对、加起来正好 100%,但拿去当条宽就是
     * <b>负段画不出来 + 正段溢出容器</b>。v1.22 在饼图上解决过同一个问题。</p>
     *
     * <p><b>不许取绝对值</b>:那会把一笔退款画成一笔消费,而且不报错 ——
     * 这个项目有过明确的教训(改写比拒绝危险,因为它无声)。</p>
     */
    @Test
    @DisplayName("负档:pct 保留真值(三档和 = 100%),barPct 按正值归一且负档不进条")
    void negativePartKeepsTruePctButDrawsNoBar() {
        var svc2 = new NormalExpenseService(null, null, null, null, null);
        // 直接验 record 的契约(split() 的取数要查库,这里只钉「两个百分比各管什么」)
        var rigid = new NormalExpenseService.SplitPart(
                com.family.finance.domain.expense.ExpenseNature.RIGID,
                new BigDecimal("40"), new BigDecimal("0.4"), new BigDecimal("0.4"), true,
                1, "#000", "#fff", false);
        var oneOff = new NormalExpenseService.SplitPart(
                com.family.finance.domain.expense.ExpenseNature.ONE_OFF,
                new BigDecimal("-360"), new BigDecimal("-3.6"), BigDecimal.ZERO, false,
                1, "#000", "#000", true);
        assertThat(oneOff.inBar()).as("负档不进条 —— 条没有负宽度").isFalse();
        assertThat(oneOff.barPct()).as("负档宽度 0,不是绝对值").isEqualByComparingTo("0");
        assertThat(oneOff.pct()).as("图例里仍是真实的负占比,不许美化").isEqualByComparingTo("-3.6");
        assertThat(rigid.inBar()).isTrue();

        var split = new NormalExpenseService.Split(
                java.util.List.of(rigid, oneOff), new BigDecimal("10000"), 0, new BigDecimal("0.4"));
        assertThat(split.refundedParts()).as("页面靠它决定要不要出「退款多于消费」那句说明")
                .containsExactly(oneOff);
    }

    // ───────────────────────── FR-673 · 三种形态 ─────────────────────────

    @Test
    @DisplayName("FR-673 · 三种形态 + 开关关闭,各自明确 —— 不能只说「自动」")
    void shapesAreExplicit() {
        assertThat(svc.shapeOf(false, true, 10)).isEqualTo(ExpenseSectionViewService.Shape.DISABLED);
        assertThat(svc.shapeOf(true, false, 0)).isEqualTo(ExpenseSectionViewService.Shape.TOTAL_ONLY);
        assertThat(svc.shapeOf(true, true, 0))
                .isEqualTo(ExpenseSectionViewService.Shape.ITEMIZED_NO_CATEGORY);
        assertThat(svc.shapeOf(true, true, 1)).isEqualTo(ExpenseSectionViewService.Shape.FULL);
    }
}
