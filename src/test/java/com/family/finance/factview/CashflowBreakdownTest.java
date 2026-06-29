package com.family.finance.factview;

import com.family.finance.calc.PnlCalculator;
import com.family.finance.domain.account.AccountClass;
import com.family.finance.domain.account.AccountLiquidity;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.period.PeriodType;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.FactMapper;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper.SinglePeriodAggregate;
import com.family.finance.service.ProductCategoryService;
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
 * v0.10 · {@code cashflowBreakdown} 口径护栏:PMC 优先(成员两框 · 本位币 → view)· 空则回退 account cash_flow。
 */
class CashflowBreakdownTest {

    private static final BigDecimal Z = BigDecimal.ZERO;
    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private FamilyMapper famMapper() {
        FamilyMapper fm = mock(FamilyMapper.class);
        Family fam = mock(Family.class);
        when(fam.getBaseCurrency()).thenReturn("CNY");
        when(fm.findById(anyLong())).thenReturn(Optional.of(fam));
        return fm;
    }

    private FactViewServiceImpl svc(PeriodMemberCashflowMapper pmc) {
        return new FactViewServiceImpl(mock(FactMapper.class), famMapper(), pmc,
                mock(AccountMapper.class), mock(ProductCategoryService.class));
    }

    /** P2 单账户:cash_flow 收入 8000 / 支出 3000(CNY 本位币,k=1)。 */
    private FactSlice cnySlice() {
        LocalDate ps = LocalDate.of(2025, 6, 1);
        BigDecimal pnl = PnlCalculator.periodPnl(bd("110000"), bd("100000"), bd("8000"), bd("3000"), Z, Z);
        AccountPeriodFact r = new AccountPeriodFact(
                1L, "acc1", AccountType.CASH, AccountClass.ASSET, AccountLiquidity.LIQUID, "CNY",
                null, 0, 102L, ps, ps,
                bd("100000"), bd("110000"), bd("100000"), bd("110000"),
                bd("8000"), bd("8000"), bd("3000"), bd("3000"),
                Z, Z, Z, Z,
                pnl, pnl, BigDecimal.ONE);
        FactFilter f = new FactFilter(1L, PeriodType.MONTHLY,
                LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 30), false, null, "CNY");
        return new FactSlice(f, List.of(r), List.of(102L), 102L);
    }

    @Test
    void pmcFirst_winsOverCashFlow_andSplitsGross() {
        PeriodMemberCashflowMapper pmc = mock(PeriodMemberCashflowMapper.class);
        // PMC 有人填(收入 9000 / 支出 4000 · 2 人)→ 应盖过 cash_flow 的 8000/3000
        when(pmc.findFamilyAggregateForPeriod(102L))
                .thenReturn(Optional.of(new SinglePeriodAggregate(102L, 102L, bd("9000"), bd("4000"), 2)));

        CashflowBreakdown b = svc(pmc).cashflowBreakdown(cnySlice(), 102L);
        assertThat(b.income()).as("PMC 优先 · 收入取 PMC").isEqualByComparingTo(bd("9000"));
        assertThat(b.expense()).isEqualByComparingTo(bd("4000"));
        assertThat(b.netInflow()).isEqualByComparingTo(bd("5000"));
        assertThat(b.income().subtract(b.expense())).isEqualByComparingTo(b.netInflow());
    }

    @Test
    void fallsBackToCashFlow_whenPmcEmpty() {
        PeriodMemberCashflowMapper pmc = mock(PeriodMemberCashflowMapper.class);
        when(pmc.findFamilyAggregateForPeriod(anyLong())).thenReturn(Optional.empty());

        CashflowBreakdown b = svc(pmc).cashflowBreakdown(cnySlice(), 102L);
        assertThat(b.income()).as("回退 cash_flow").isEqualByComparingTo(bd("8000"));
        assertThat(b.expense()).isEqualByComparingTo(bd("3000"));
        assertThat(b.netInflow()).isEqualByComparingTo(bd("5000"));
    }

    @Test
    void nullPeriod_returnsZeros() {
        PeriodMemberCashflowMapper pmc = mock(PeriodMemberCashflowMapper.class);
        CashflowBreakdown b = svc(pmc).cashflowBreakdown(cnySlice(), null);
        assertThat(b.income()).isEqualByComparingTo(Z);
        assertThat(b.expense()).isEqualByComparingTo(Z);
        assertThat(b.netInflow()).isEqualByComparingTo(Z);
    }
}
