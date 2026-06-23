package com.family.finance.factview;

import com.family.finance.domain.account.AccountClass;
import com.family.finance.domain.account.AccountLiquidity;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.period.PeriodType;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.FactMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper.SinglePeriodAggregate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v0.5 FR-84 防回归 · 净流入(人赚的)PMC 优先 + 守 人赚 + 钱赚 = ΔNetWorth 恒等式。
 *
 * <p>原 bug:principalVsReturnDecomposition 只读 account cash_flow(incomeBase/expenseBase)·
 * 用户工资填在 period_member_cashflow(PMC)· 故净流入恒为 0、且把工资增长误算成投资 PnL。</p>
 */
class NetInflowDecompositionTest {

    private static final BigDecimal Z = BigDecimal.ZERO;

    private FactViewServiceImpl svc(PeriodMemberCashflowMapper pmc) {
        return new FactViewServiceImpl(mock(FactMapper.class), mock(FamilyMapper.class), pmc,
                mock(com.family.finance.repository.AccountMapper.class),
                mock(com.family.finance.service.ProductCategoryService.class));
    }

    /** 一行账户事实 · 只关心 endBalanceBase / incomeBase / expenseBase,其余填 0/null。 */
    private AccountPeriodFact fact(long accId, long periodId, LocalDate ps,
                                   String endBase, String incBase, String expBase) {
        return new AccountPeriodFact(
                accId, "acc", AccountType.CASH, AccountClass.ASSET, AccountLiquidity.LIQUID, "CNY",
                null, 0, periodId, ps, ps,
                Z, new BigDecimal(endBase), Z, new BigDecimal(endBase),
                new BigDecimal(incBase), new BigDecimal(incBase),
                new BigDecimal(expBase), new BigDecimal(expBase),
                Z, Z, Z, Z, Z, Z, BigDecimal.ONE);
    }

    private FactSlice slice(List<AccountPeriodFact> rows, List<Long> periodIds) {
        FactFilter filter = new FactFilter(1L, PeriodType.MONTHLY,
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), false, null, "CNY");
        return new FactSlice(filter, rows, periodIds, null);
    }

    @Test
    void peopleEarnPlusMoneyEarnEqualsDeltaNetWorth() {
        // 3 期净资产:100w → 105w → 110w(ΔNW=10w · 从锚 p0 到 p2)
        // cash_flow 全 0(模拟用户只填 PMC 没逐笔记账)
        var rows = List.of(
                fact(1, 10, LocalDate.of(2025, 1, 1), "1000000", "0", "0"),
                fact(1, 11, LocalDate.of(2025, 2, 1), "1050000", "0", "0"),
                fact(1, 12, LocalDate.of(2025, 3, 1), "1100000", "0", "0"));
        var pmc = mock(PeriodMemberCashflowMapper.class);
        // p1 净流入 2w · p2 净流入 2w · 人赚合计 4w
        when(pmc.findFamilyAggregateForPeriod(11L)).thenReturn(Optional.of(
                new SinglePeriodAggregate(11L, 11L, new BigDecimal("30000"), new BigDecimal("10000"), 2)));
        when(pmc.findFamilyAggregateForPeriod(12L)).thenReturn(Optional.of(
                new SinglePeriodAggregate(12L, 12L, new BigDecimal("35000"), new BigDecimal("15000"), 2)));

        var decomp = svc(pmc).principalVsReturnDecomposition(slice(rows, List.of(10L, 11L, 12L)));
        var last = decomp.getLast();

        // 人赚 = PMC 净流入合计 4w(若错读 cash_flow=0,这里会是 0)
        assertThat(last.cumulativeNetInflow()).isEqualByComparingTo("40000");
        // 钱赚 = ΔNW − 人赚 = 10w − 4w = 6w
        assertThat(last.cumulativePnl()).isEqualByComparingTo("60000");
        // 恒等式:人赚 + 钱赚 = ΔNetWorth(110w − 100w = 10w)
        assertThat(last.cumulativeNetInflow().add(last.cumulativePnl())).isEqualByComparingTo("100000");
    }

    @Test
    void pmcFirstNotZeroWhenCashFlowEmpty() {
        // 复现 prod bug:cash_flow=0 但 PMC 有收入 → 净流入必须非 0
        var rows = List.of(
                fact(1, 10, LocalDate.of(2025, 1, 1), "500000", "0", "0"),
                fact(1, 11, LocalDate.of(2025, 2, 1), "560000", "0", "0"));
        var pmc = mock(PeriodMemberCashflowMapper.class);
        when(pmc.findFamilyAggregateForPeriod(11L)).thenReturn(Optional.of(
                new SinglePeriodAggregate(11L, 11L, new BigDecimal("50000"), new BigDecimal("8000"), 2)));

        var decomp = svc(pmc).principalVsReturnDecomposition(slice(rows, List.of(10L, 11L)));

        // 净流入 = 50000 − 8000 = 42000(非 0)
        assertThat(decomp.getLast().cumulativeNetInflow()).isEqualByComparingTo("42000");
    }

    @Test
    void fallsBackToCashFlowWhenPmcEmpty() {
        // 老数据:无 PMC(filledMembers=0)→ 回退 account cash_flow
        var rows = List.of(
                fact(1, 10, LocalDate.of(2025, 1, 1), "500000", "0", "0"),
                fact(1, 11, LocalDate.of(2025, 2, 1), "560000", "25000", "5000"));
        var pmc = mock(PeriodMemberCashflowMapper.class);
        // PMC 空:filledMembers=0(或 Optional.empty)
        when(pmc.findFamilyAggregateForPeriod(anyLong())).thenReturn(Optional.of(
                new SinglePeriodAggregate(null, null, null, null, 0)));

        var decomp = svc(pmc).principalVsReturnDecomposition(slice(rows, List.of(10L, 11L)));

        // 回退 cash_flow:25000 − 5000 = 20000
        assertThat(decomp.getLast().cumulativeNetInflow()).isEqualByComparingTo("20000");
    }
}
