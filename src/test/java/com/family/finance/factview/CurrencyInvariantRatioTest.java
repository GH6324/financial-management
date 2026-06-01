package com.family.finance.factview;

import com.family.finance.domain.account.AccountClass;
import com.family.finance.domain.account.AccountLiquidity;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.period.PeriodType;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.FactMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper.FamilyPeriodAggregate;
import com.family.finance.repository.PeriodMemberCashflowMapper.SinglePeriodAggregate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v0.5 修 · 比值类指标(紧急储备月数 / 本月资产收益%)必须币种无关。
 *
 * <p>bug:endBalanceBase 是 viewCurrency 口径,但 PMC(月支出/净流入)按本位币存、未换算,
 * 切到非本位币时 比值 = 余额(view) / PMC(base) 漂移。修:PMC × baseToViewFactor。</p>
 */
class CurrencyInvariantRatioTest {

    private static final BigDecimal Z = BigDecimal.ZERO;
    private FamilyMapper familyMapper;
    private PeriodMemberCashflowMapper pmc;
    private FactMapper factMapper;

    @BeforeEach
    void setUp() {
        familyMapper = mock(FamilyMapper.class);
        pmc = mock(PeriodMemberCashflowMapper.class);
        factMapper = mock(FactMapper.class);
        when(familyMapper.findById(1L)).thenReturn(Optional.of(
                Family.builder().id(1L).baseCurrency("CNY").build()));
        // 月支出 PMC = 20000(本位币 CNY)· 近 12 月聚合
        when(pmc.findFamilyAggregateRecent(eq(1L), anyInt())).thenReturn(List.of(
                new FamilyPeriodAggregate(11L, LocalDate.of(2026, 5, 1), new BigDecimal("50000"), new BigDecimal("20000"), 2)));
        // 当期净流入 PMC:收入 50000 − 支出 20000 = 30000(本位币)
        when(pmc.findFamilyAggregateForPeriod(anyLong())).thenReturn(Optional.of(
                new SinglePeriodAggregate(11L, 11L, new BigDecimal("50000"), new BigDecimal("20000"), 2)));
    }

    private FactViewServiceImpl svc() {
        return new FactViewServiceImpl(factMapper, familyMapper, pmc);
    }

    /** 一条本位币(或换算后)流动资产账户事实。fxToBase = base→view 因子。 */
    private AccountPeriodFact liquidFact(long periodId, LocalDate ps, String endBalanceView, String fxToBase) {
        return new AccountPeriodFact(
                1L, "现金", AccountType.CASH, AccountClass.ASSET, AccountLiquidity.LIQUID, "CNY",
                null, 0, periodId, ps, ps,
                Z, new BigDecimal(endBalanceView), Z, new BigDecimal(endBalanceView),
                Z, Z, Z, Z, Z, Z, Z, Z, Z, Z, new BigDecimal(fxToBase));
    }

    private FactSlice slice(String view, String prevBalView, String lastBalView, String fx) {
        FactFilter f = new FactFilter(1L, PeriodType.MONTHLY,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 5, 1), false, null, view);
        var rows = List.of(
                liquidFact(10L, LocalDate.of(2026, 4, 1), prevBalView, fx),
                liquidFact(11L, LocalDate.of(2026, 5, 1), lastBalView, fx));
        return new FactSlice(f, rows, List.of(10L, 11L), 11L);
    }

    @Test
    void emergencyMonthsInvariantAcrossCurrency() {
        // CNY 视图:流动资产 110万 / 月支出 2万 = 55 个月
        var cny = svc().kpis(slice("CNY", "1000000", "1100000", "1"));
        // USD 视图:余额×0.14、PMC 也应 ×0.14 → 154000/2800 = 55(不变)
        var usd = svc().kpis(slice("USD", "140000", "154000", "0.14"));

        assertThat(cny.emergencyFundMonths()).isEqualByComparingTo("55.0");
        assertThat(usd.emergencyFundMonths()).isEqualByComparingTo("55.0");  // 修复前会是 154000/20000=7.7
        assertThat(usd.emergencyFundMonths()).isEqualByComparingTo(cny.emergencyFundMonths());
    }

    @Test
    void monthlyInvestReturnInvariantAcrossCurrency() {
        // CNY:(110万 − 100万 − 净流入3万) / 100万 = 7%
        var cny = svc().kpis(slice("CNY", "1000000", "1100000", "1"));
        // USD:(154000 − 140000 − 净流入4200) / 140000 = 7%(不变)
        var usd = svc().kpis(slice("USD", "140000", "154000", "0.14"));

        assertThat(cny.monthlyInvestReturnPct()).isNotNull();
        assertThat(usd.monthlyInvestReturnPct()).isEqualByComparingTo(cny.monthlyInvestReturnPct());
    }

    @Test
    void debtRatioAlreadyInvariant() {
        // 负债率两侧都来自 endBalanceBase(view)· 本就不变 · 回归保护
        var cny = svc().kpis(slice("CNY", "1000000", "1100000", "1"));
        var usd = svc().kpis(slice("USD", "140000", "154000", "0.14"));
        assertThat(cny.debtToAssetRatio()).isEqualByComparingTo(
                usd.debtToAssetRatio() == null ? BigDecimal.ZERO : usd.debtToAssetRatio());
    }
}
