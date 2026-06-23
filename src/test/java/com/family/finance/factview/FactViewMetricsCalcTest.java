package com.family.finance.factview;

import com.family.finance.calc.PnlCalculator;
import com.family.finance.domain.account.AccountClass;
import com.family.finance.domain.account.AccountLiquidity;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.period.PeriodType;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.FactMapper;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper;
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
 * v0.8 QA · 可配置账户级指标的**计算正确性**(以 QA 视角:单月 / 多月 / 带外部流入 / 带转账 后,
 * 各指标算得对不对)。手构造已知账户事实序列(periodPnl 走真实 {@link PnlCalculator}),
 * 跑 {@code accountPerformance} 断言精确值。
 *
 * 关键被测口径:
 *  - 累计投资损益 cumPnl = Σ periodPnl(剔除外部流入 + 转账)
 *  - 累计净投入 netPrincipal = Σ(income − expense + transferIn − transferOut)
 *  - 本期Δ momAmount = 末期末余额 − 上期末余额
 *  - 占比 sharePct = 账户现值 ÷ 家庭净资产
 *  - 转账绝不产生「幽灵损益」(转出/转入端 cumPnl 各自为 0)
 */
class FactViewMetricsCalcTest {

    private static final BigDecimal Z = BigDecimal.ZERO;

    private FactViewServiceImpl svc() {
        AccountMapper am = mock(AccountMapper.class);
        when(am.findById(anyLong())).thenReturn(Optional.empty());   // 无 expected → 预实 null,不影响本测
        return new FactViewServiceImpl(mock(FactMapper.class), mock(FamilyMapper.class),
                mock(PeriodMemberCashflowMapper.class), am, mock(ProductCategoryService.class));
    }

    /** 一行账户事实:orig==base(fx=1),periodPnl 用真实公式算(首期 prev=null→null)。 */
    private AccountPeriodFact fact(long accId, long periodId, int monthIdx,
                                   String prevEnd, String end,
                                   String income, String expense, String tIn, String tOut) {
        LocalDate ps = LocalDate.of(2025, monthIdx, 1);
        BigDecimal prev = prevEnd == null ? null : new BigDecimal(prevEnd);
        BigDecimal e = new BigDecimal(end);
        BigDecimal inc = new BigDecimal(income), exp = new BigDecimal(expense);
        BigDecimal tin = new BigDecimal(tIn), tout = new BigDecimal(tOut);
        BigDecimal pnl = PnlCalculator.periodPnl(e, prev, inc, exp, tin, tout);
        return new AccountPeriodFact(
                accId, "acc" + accId, AccountType.CASH, AccountClass.ASSET, AccountLiquidity.LIQUID, "CNY",
                null, 0, periodId, ps, ps,
                prev, e, prev, e,
                inc, inc, exp, exp,
                tin, tin, tout, tout,
                pnl, pnl, BigDecimal.ONE);
    }

    private FactSlice slice(List<AccountPeriodFact> rows, List<Long> periodIds) {
        FactFilter f = new FactFilter(1L, PeriodType.MONTHLY,
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), false, null, "CNY");
        return new FactSlice(f, rows, periodIds, periodIds.isEmpty() ? null : periodIds.get(periodIds.size() - 1));
    }

    private AccountPerformance only(FactSlice s) {
        return svc().accountPerformance(s).get(0);
    }

    @Test
    void singleMonth_noFlows_noCumPnl() {
        AccountPerformance p = only(slice(
                List.of(fact(1, 101, 1, null, "10000", "0", "0", "0", "0")), List.of(101L)));
        assertThat(p.currentValue()).isEqualByComparingTo("10000");
        assertThat(p.monthsHeld()).isEqualTo(1);
        assertThat(p.cumPnl()).isEqualByComparingTo("0");   // 首期无 prev → 无损益
        assertThat(p.netPrincipal()).isEqualByComparingTo("0");
        assertThat(p.momAmount()).isNull();                 // 只有 1 个有余额期
        assertThat(p.xirr()).isNull();                      // <2 期
        assertThat(p.sparklinePoints()).isNull();
        assertThat(p.sharePct()).isEqualByComparingTo("100.00");  // 唯一账户
    }

    @Test
    void multiMonth_pureValuation_cumPnlSumsGains() {
        // 10000 → 10200(+200)→ 10404(+204),无任何流水 → 全是投资损益
        AccountPerformance p = only(slice(List.of(
                fact(1, 101, 1, null, "10000", "0", "0", "0", "0"),
                fact(1, 102, 2, "10000", "10200", "0", "0", "0", "0"),
                fact(1, 103, 3, "10200", "10404", "0", "0", "0", "0")), List.of(101L, 102L, 103L)));
        assertThat(p.cumPnl()).isEqualByComparingTo("404");      // 200 + 204
        assertThat(p.netPrincipal()).isEqualByComparingTo("0");
        assertThat(p.monthsHeld()).isEqualTo(3);
        assertThat(p.momAmount()).isEqualByComparingTo("204");   // 10404 − 10200
        assertThat(p.xirr()).isNotNull();                        // <12 期 → 累计收益率,非 null
        assertThat(p.sparklinePoints()).isNotNull();
    }

    @Test
    void externalIncome_excludedFromPnl_countedInPrincipal() {
        // 10000 → 15000,其中 4000 是工资存入(income)→ 真实投资损益只有 1000
        AccountPerformance p = only(slice(List.of(
                fact(1, 101, 1, null, "10000", "0", "0", "0", "0"),
                fact(1, 102, 2, "10000", "15000", "4000", "0", "0", "0")), List.of(101L, 102L)));
        assertThat(p.cumPnl()).isEqualByComparingTo("1000");      // 15000−10000−4000 = 1000(剔除工资)
        assertThat(p.netPrincipal()).isEqualByComparingTo("4000");
        assertThat(p.momAmount()).isEqualByComparingTo("5000");   // 余额变化(含本金)
    }

    @Test
    void transferOut_noPhantomPnl_reducesPrincipal() {
        // 10000 → 7000,3000 是转出到别的账户 → 不是亏损,损益应为 0
        AccountPerformance p = only(slice(List.of(
                fact(1, 101, 1, null, "10000", "0", "0", "0", "0"),
                fact(1, 102, 2, "10000", "7000", "0", "0", "0", "3000")), List.of(101L, 102L)));
        assertThat(p.cumPnl()).isEqualByComparingTo("0");          // 转账不产生幽灵损益
        assertThat(p.netPrincipal()).isEqualByComparingTo("-3000"); // 净投入减少
    }

    @Test
    void transferIn_noPhantomPnl_increasesPrincipal() {
        // 5000 → 8000,3000 是从别处转入 → 不是收益,损益应为 0
        AccountPerformance p = only(slice(List.of(
                fact(2, 201, 1, null, "5000", "0", "0", "0", "0"),
                fact(2, 202, 2, "5000", "8000", "0", "0", "3000", "0")), List.of(201L, 202L)));
        assertThat(p.cumPnl()).isEqualByComparingTo("0");
        assertThat(p.netPrincipal()).isEqualByComparingTo("3000");
    }

    @Test
    void sharePct_relativeToFamilyNetWorth() {
        // 两账户同期:A=7000,B=8000 → 家庭净资产 15000
        FactSlice s = slice(List.of(
                fact(1, 102, 2, "10000", "7000", "0", "0", "0", "3000"),
                fact(2, 102, 2, "5000", "8000", "0", "0", "3000", "0")), List.of(102L));
        List<AccountPerformance> rows = svc().accountPerformance(s);
        AccountPerformance a = rows.stream().filter(r -> r.accountId() == 1L).findFirst().orElseThrow();
        AccountPerformance b = rows.stream().filter(r -> r.accountId() == 2L).findFirst().orElseThrow();
        assertThat(a.sharePct()).isEqualByComparingTo("46.67");   // 7000/15000
        assertThat(b.sharePct()).isEqualByComparingTo("53.33");   // 8000/15000
        // 家庭层面转账净为零:A 转出 3000 = B 转入 3000,两端损益均 0
        assertThat(a.cumPnl()).isEqualByComparingTo("0");
        assertThat(b.cumPnl()).isEqualByComparingTo("0");
    }
}
