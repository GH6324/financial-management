package com.family.finance.service;

import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.repository.PeriodMemberCashflowMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper.FamilyPeriodAggregate;
import com.family.finance.service.expense.ExpenseLedgerService;
import com.family.finance.service.expense.ExpenseLedgerService.PeriodExpense;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.18.7 · 储蓄率必须<b>连它算的是哪一期一起交出来</b>。
 *
 * <h3>为什么要有这条</h3>
 * <p>这个数在仪表盘上写着「<b>本期</b>储蓄率」,而两条取数路径<b>都可能不是本期</b>:</p>
 * <ol>
 *   <li>PMC 分支取「最近一个<b>有 PMC 记录</b>的期」—— 月初还没人填收支时它就是<b>上个月</b></li>
 *   <li>兜底分支走 {@code factViewService.savingsRate(loadDefault(...))},那个方法锚
 *       {@code returnAnchorPeriodId()} = <b>最新已关账期</b> —— 一定不是进行中的本期</li>
 * </ol>
 *
 * <p>beta 实测(v1.18.6):本期 2026-08 有 51 笔收入、<b>0 笔支出</b> → 本期储蓄率必然 100%,
 * 而页面显示 98.4%。它显示的根本不是本期,页面上却<b>一个字的标注都没有</b> ——
 * 还和同一句话里<b>实时</b>的净资产/环比混在一起。</p>
 *
 * <p>维护者定:<b>不改口径,把账期标出来</b>。所以这条测试钉的是
 * 「{@code periodId} 必须如实反映数据来自哪一期」,<b>不是</b>「必须等于本期」。</p>
 */
class SavingsRatePeriodTest {

    private static final long FAM = 1L;

    private final PeriodMemberCashflowMapper pmc = mock(PeriodMemberCashflowMapper.class);
    private final FactViewService factView = mock(FactViewService.class);
    private final ExpenseLedgerService ledger = mock(ExpenseLedgerService.class);

    private HouseholdCashflowService svc() {
        // cashIncomeByPeriod 会去 loadDefault 取 cash_flow 侧的收入 —— 默认给一张空切片,
        // 让 PMC 分支走 incomeBlend 的「PMC 有值就用 PMC」那条路。
        FactSlice empty = mock(FactSlice.class);
        when(empty.rows()).thenReturn(List.of());
        when(empty.periodIds()).thenReturn(List.of());
        when(factView.loadDefault(anyLong())).thenReturn(empty);
        return new HouseholdCashflowService(pmc, factView, ledger);
    }

    private static FamilyPeriodAggregate agg(long periodId, int month, String income, String expense) {
        return new FamilyPeriodAggregate(periodId, LocalDate.of(2026, month, 1),
                new BigDecimal(income), new BigDecimal(expense), 1);
    }

    // ──────────────────────── PMC 分支 ────────────────────────

    /** 最近一个有 PMC 记录的期是 7 月 → 数值算 7 月的,{@code periodId} 也必须说自己是 7 月。 */
    @Test
    void PMC分支_如实报出它算的是哪一期() {
        when(pmc.findFamilyAggregateRecent(anyLong(), anyInt()))
                .thenReturn(List.of(agg(20L, 7, "25000", "1000")));
        when(ledger.byPeriod(anyLong(), anyLong()))
                .thenReturn(new PeriodExpense(20L, LocalDate.of(2026, 7, 1),
                        new BigDecimal("1000"), PeriodExpense.Source.TOTAL, 3));

        var v = svc().savingsRateView(FAM);
        assertThat(v.periodId()).as("必须点名是 7 月那一期").isEqualTo(20L);
        assertThat(v.periodStart()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(v.rate()).isEqualByComparingTo("0.96");   // (25000 − 1000) / 25000
    }

    /**
     * <b>这条是本次的核心</b>:本期(8 月)一条 PMC 都没有,取到的是 7 月 ——
     * 页面此前把它写成「本期储蓄率」。现在必须能看出来它是 7 月的。
     */
    @Test
    void 本期没收支记录时_取到的是上一期_而且说得出来() {
        when(pmc.findFamilyAggregateRecent(anyLong(), anyInt()))
                .thenReturn(List.of(agg(20L, 7, "25000", "1000")));
        when(ledger.byPeriod(anyLong(), anyLong()))
                .thenReturn(new PeriodExpense(20L, LocalDate.of(2026, 7, 1),
                        new BigDecimal("1000"), PeriodExpense.Source.TOTAL, 3));

        var v = svc().savingsRateView(FAM);
        assertThat(v.periodStart().getMonthValue())
                .as("拿到的是 7 月的数;调用方据此渲染「2026-07 账期储蓄率」,而不是「本期储蓄率」")
                .isEqualTo(7);
    }

    // ──────────────────────── 兜底分支 ────────────────────────

    /**
     * PMC 全空 → 走 {@code savingsRate(loadDefault)},它锚<b>最新已关账期</b>。
     * 这条分支此前完全看不出期次,现在要报出锚点期。
     */
    @Test
    void 兜底分支_报出的是已关账锚点期() {
        when(pmc.findFamilyAggregateRecent(anyLong(), anyInt())).thenReturn(List.of());
        FactSlice slice = mock(FactSlice.class);
        when(slice.returnAnchorPeriodId()).thenReturn(20L);
        when(slice.periodStartOf(20L)).thenReturn(LocalDate.of(2026, 7, 1));
        var s = svc();
        when(factView.loadDefault(anyLong())).thenReturn(slice);
        when(factView.savingsRate(any())).thenReturn(new BigDecimal("0.984"));

        var v = s.savingsRateView(FAM);
        assertThat(v.rate()).isEqualByComparingTo("0.984");
        assertThat(v.periodId()).as("兜底分支也要说清是哪一期 —— 它锚的是最新已关账期").isEqualTo(20L);
        assertThat(v.periodStart()).isEqualTo(LocalDate.of(2026, 7, 1));
    }

    // ──────────────────────── 不许变的 ────────────────────────

    /**
     * <b>口径一个字没改</b> —— 老入口 {@code currentSavingsRate} 必须与新入口逐位相同。
     * 这一版只加了「说清是哪一期」,没有动数值;若哪天有人顺手把锚改了,这条会红。
     */
    @Test
    void 数值口径与老入口逐位一致() {
        when(pmc.findFamilyAggregateRecent(anyLong(), anyInt()))
                .thenReturn(List.of(agg(20L, 7, "25000", "1000")));
        when(ledger.byPeriod(anyLong(), anyLong()))
                .thenReturn(new PeriodExpense(20L, LocalDate.of(2026, 7, 1),
                        new BigDecimal("1000"), PeriodExpense.Source.TOTAL, 3));
        var svc = svc();
        assertThat(svc.currentSavingsRate(FAM)).isEqualByComparingTo(svc.savingsRateView(FAM).rate());
    }

    /** 算不出来时老实给 null,不许拿 0 冒充 —— 0% 储蓄率和「没数据」是两件事。 */
    @Test
    void 算不出来时给null_不许拿零冒充() {
        when(pmc.findFamilyAggregateRecent(anyLong(), anyInt())).thenReturn(List.of());
        FactSlice slice = mock(FactSlice.class);
        when(slice.returnAnchorPeriodId()).thenReturn(null);
        var s = svc();
        when(factView.loadDefault(anyLong())).thenReturn(slice);
        when(factView.savingsRate(any())).thenReturn(null);

        var v = s.savingsRateView(FAM);
        assertThat(v.rate()).isNull();
        assertThat(v.periodStart()).isNull();
    }
}
