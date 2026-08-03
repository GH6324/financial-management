package com.family.finance.service.expense;

import com.family.finance.domain.family.ExpenseEntryMode;
import com.family.finance.domain.family.Family;
import com.family.finance.repository.CashFlowMapper;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.8 · 家庭支出口径(PRD FR-273)的判定规则。
 *
 * <p>这些断言守的是「口径只有一份实现」这件事本身 —— v1.6.29 那次「同页两套收入口径」
 * 就是因为判断散落在各调用点。</p>
 */
class ExpenseLedgerServiceTest {

    private CashFlowMapper cf;
    private PeriodMemberCashflowMapper pmc;

    /** 逐笔模式(用户显式切过去)—— 逐笔优先。 */
    private ExpenseLedgerService itemizedSvc(List<CashFlowMapper.RealExpenseSum> itemized,
                                    List<PeriodMemberCashflowMapper.FamilyPeriodAggregate> totals) {
        return svc(itemized, totals, ExpenseEntryMode.ITEMIZED);
    }

    /** 总额模式(默认 · 存量家庭)—— PMC 优先,必须与 v1.8 之前逐位一致。 */
    private ExpenseLedgerService totalSvc(List<CashFlowMapper.RealExpenseSum> itemized,
                                    List<PeriodMemberCashflowMapper.FamilyPeriodAggregate> totals) {
        return svc(itemized, totals, ExpenseEntryMode.TOTAL);
    }

    private ExpenseLedgerService svc(List<CashFlowMapper.RealExpenseSum> itemized,
                                    List<PeriodMemberCashflowMapper.FamilyPeriodAggregate> totals,
                                    ExpenseEntryMode mode) {
        cf = mock(CashFlowMapper.class);
        pmc = mock(PeriodMemberCashflowMapper.class);
        FamilyMapper fam = mock(FamilyMapper.class);
        when(cf.sumRealExpenseByPeriod(anyLong(), any())).thenReturn(itemized);
        when(pmc.findFamilyAggregateRecent(anyLong(), anyInt())).thenReturn(totals);
        // 单期查询走 findFamilyAggregateForPeriod(与 v1.8 之前 netInflowExpense 同源)→ 由 totals 派生
        when(pmc.findFamilyAggregateForPeriod(anyLong())).thenAnswer(inv -> {
            long pid = inv.getArgument(0);
            return totals.stream().filter(t -> t.periodId() == pid).findFirst()
                    .map(t -> new PeriodMemberCashflowMapper.SinglePeriodAggregate(
                            t.periodId(), t.periodId(), BigDecimal.ZERO, t.totalExpense(), t.filledMembers()));
        });
        Family f = new Family();
        f.setExpenseEntryMode(mode.name());
        when(fam.findById(anyLong())).thenReturn(Optional.of(f));
        return new ExpenseLedgerService(cf, pmc, fam);
    }

    private CashFlowMapper.RealExpenseSum item(long periodId, String amount, int cnt) {
        // periodStart 用 periodId 反推一个稳定日期(2026 年内),让排序可预期
        return new CashFlowMapper.RealExpenseSum(periodId, new BigDecimal(amount), cnt,
                LocalDate.of(2026, (int) periodId, 1));
    }

    private PeriodMemberCashflowMapper.FamilyPeriodAggregate total(long periodId, int month, String expense) {
        return new PeriodMemberCashflowMapper.FamilyPeriodAggregate(
                periodId, LocalDate.of(2026, month, 1), BigDecimal.ZERO, new BigDecimal(expense), 1);
    }

    @Test
    void 有逐笔时以逐笔为准_不与总额相加() {
        var s = itemizedSvc(List.of(item(1L, "6800", 3)), List.of(total(1L, 8, "10000")));
        var pe = s.byPeriod(1L, 1L);
        assertThat(pe.source()).isEqualTo(ExpenseLedgerService.PeriodExpense.Source.ITEMIZED);
        assertThat(pe.amountBase()).isEqualByComparingTo("6800");   // 不是 16800,也不是 10000
        assertThat(pe.itemCount()).isEqualTo(3);
    }

    @Test
    void 无逐笔时回落总额() {
        var s = itemizedSvc(List.of(), List.of(total(1L, 8, "10000")));
        var pe = s.byPeriod(1L, 1L);
        assertThat(pe.source()).isEqualTo(ExpenseLedgerService.PeriodExpense.Source.TOTAL);
        assertThat(pe.amountBase()).isEqualByComparingTo("10000");
        assertThat(pe.itemCount()).isZero();
    }

    @Test
    void 两者都没有时是NONE且不算已填() {
        var s = itemizedSvc(List.of(), List.of());
        var pe = s.byPeriod(1L, 1L);
        assertThat(pe.source()).isEqualTo(ExpenseLedgerService.PeriodExpense.Source.NONE);
        assertThat(pe.amountBase()).isEqualByComparingTo("0");
        assertThat(pe.filled()).isFalse();
    }

    @Test
    void 金额为0的逐笔不得压掉用户手填的总额() {
        // 判据是「逐笔合计 > 0」而不是「有逐笔行」—— 否则一行 0 元会把总额顶掉,支出凭空变 0
        var s = itemizedSvc(List.of(item(1L, "0", 1)), List.of(total(1L, 8, "10000")));
        var pe = s.byPeriod(1L, 1L);
        assertThat(pe.source()).isEqualTo(ExpenseLedgerService.PeriodExpense.Source.TOTAL);
        assertThat(pe.amountBase()).isEqualByComparingTo("10000");
    }

    @Test
    void 逐笔独有的账期也要进recent_否则月均偏小() {
        // period 2 只有逐笔、PMC 里没有;若 recent 只看 PMC,这个月会整个漏掉
        var s = itemizedSvc(List.of(item(1L, "3000", 2), item(2L, "5000", 4)),
                    List.of(total(1L, 7, "9999")));
        var list = s.recent(1L, 12);
        assertThat(list).hasSize(2);
        assertThat(list).allMatch(ExpenseLedgerService.PeriodExpense::filled);
        assertThat(list).extracting(pe -> pe.amountBase().stripTrailingZeros().toPlainString())
                .containsExactlyInAnyOrder("3000", "5000");   // 逐笔优先,9999 不采用
    }

    @Test
    void recent按limit截断且跳过未填的期() {
        var s = itemizedSvc(List.of(), List.of(total(1L, 8, "100"), total(2L, 7, "200"), total(3L, 6, "0")));
        var list = s.recent(1L, 2);
        assertThat(list).hasSize(2);                    // period 3 总额为 0 → NONE → 不计入
        assertThat(list.get(0).amountBase()).isEqualByComparingTo("100");   // 8 月在前(倒序)
    }

    // ── v1.8 开发中被「总额模式逐位比对」拦下的 bug:优先级不能无条件逐笔优先 ──
    // beta 紧急储备 8.0 月→1.9 月;prod 2026-06 的 PMC 总额 ¥32,797 会被 ¥3,000 的逐笔顶掉(少算 89%)。
    // 根因:cash_flow 里的 EXPENSE 行来自账户级流水那条老路径,不是「用户主动选择逐笔录入」。

    @Test
    void 总额模式下逐笔不得顶掉PMC总额() {
        var s = totalSvc(List.of(item(1L, "3000", 1)), List.of(total(1L, 6, "32797.17")));
        var pe = s.byPeriod(1L, 1L);
        assertThat(pe.source()).isEqualTo(ExpenseLedgerService.PeriodExpense.Source.TOTAL);
        assertThat(pe.amountBase()).isEqualByComparingTo("32797.17");   // 不是 3000
    }

    @Test
    void 总额模式下取期集合只看PMC_分母不能变() {
        // period 2 只有逐笔。ITEMIZED 会并进来(2 期),TOTAL 必须只算 PMC 那 1 期,
        // 否则月均支出的分母变了 —— 即使每期取值都对,均值也会和 v1.8 之前不同。
        var itemized = List.of(item(1L, "3000", 2), item(2L, "5000", 4));
        var totals = List.of(total(1L, 7, "9999"));
        assertThat(totalSvc(itemized, totals).recent(1L, 12)).hasSize(1);
        assertThat(itemizedSvc(itemized, totals).recent(1L, 12)).hasSize(2);
    }

    @Test
    void 总额模式下无PMC时返回NONE_把兜底交回调用方() {
        // 故意不在口径服务里兜底 cash_flow:调用方原本回落的是**事实切片**(排归档 + 已换汇),
        // 而本服务的逐笔 SQL 是另一套过滤。让调用方走自己的老路,「总额模式逐位不变」才是结构性成立的。
        var s = totalSvc(List.of(item(1L, "3000", 2)), List.of());
        var pe = s.byPeriod(1L, 1L);
        assertThat(pe.source()).isEqualTo(ExpenseLedgerService.PeriodExpense.Source.NONE);
        assertThat(pe.filled()).isFalse();
    }

    @Test
    void 模式脏值或家庭缺失时兜底总额模式() {
        FamilyMapper fam = mock(FamilyMapper.class);
        when(fam.findById(anyLong())).thenReturn(Optional.empty());
        var s = new ExpenseLedgerService(mock(CashFlowMapper.class), mock(PeriodMemberCashflowMapper.class), fam);
        assertThat(s.modeOf(1L)).isEqualTo(ExpenseEntryMode.TOTAL);
    }

    @Test
    void 批量查询对每个传入期都返回一项() {
        var s = itemizedSvc(List.of(item(2L, "500", 1)), List.of(total(1L, 8, "700")));
        var map = s.byPeriods(1L, List.of(1L, 2L, 3L));
        assertThat(map).containsOnlyKeys(1L, 2L, 3L);
        assertThat(map.get(1L).source()).isEqualTo(ExpenseLedgerService.PeriodExpense.Source.TOTAL);
        assertThat(map.get(2L).source()).isEqualTo(ExpenseLedgerService.PeriodExpense.Source.ITEMIZED);
        assertThat(map.get(3L).source()).isEqualTo(ExpenseLedgerService.PeriodExpense.Source.NONE);
    }
}
