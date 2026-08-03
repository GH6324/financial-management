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
import com.family.finance.repository.SnapshotMapper;
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
 * v1.6.30 · 收益类指标必须锚「最新已关账期」,不受进行中(OPEN)账期影响。
 *
 * <p>起因(2026-08-01 全站指标核查 P0):{@code queryBase} 是 account × period 全交叉且
 * <b>不过滤 period.status</b>,所以进行中的期会进切片并成为 {@code lastPeriodId}。
 * 而进行中的期典型状态是<b>余额已填、收支未录</b>(prod 2026-08 实测:21 条余额 / 0 条收支),
 * 于是 (期末 − 期初 − 净流入) 里净流入 = 0,<b>把还没录的工资整块算成投资收益</b>。</p>
 *
 * <p>本测用同一份事实数据造两个切片 —— 一个把末期标为已关账、一个标为进行中 ——
 * 断言:存量类(净资产)两者相同;收益类(本月资产收益 / XIRR / TWR / 人赚钱赚拆解)
 * 在"进行中"的切片里必须退回到上一个已关账期的结果。</p>
 */
class ClosedPeriodAnchorTest {

    private static final BigDecimal Z = BigDecimal.ZERO;

    private FactViewServiceImpl svc() {
        AccountMapper am = mock(AccountMapper.class);
        when(am.findById(anyLong())).thenReturn(Optional.empty());
        PeriodMemberCashflowMapper pmc = mock(PeriodMemberCashflowMapper.class);
        when(pmc.findFamilyAggregateForPeriod(anyLong())).thenReturn(Optional.empty());
        SnapshotMapper sm = mock(SnapshotMapper.class);
        // 无「首次出现」账户 → 开账基线恒 0,把本测聚焦在锚点上
        when(sm.firstAppearingAccountIds(anyLong(), anyLong())).thenReturn(List.of());
        return new FactViewServiceImpl(mock(FactMapper.class), mock(FamilyMapper.class),
                pmc, am, mock(ProductCategoryService.class), sm,
                new com.family.finance.service.expense.ExpenseLedgerService(
                        mock(com.family.finance.repository.CashFlowMapper.class), pmc,
                        mock(com.family.finance.repository.FamilyMapper.class),
                        mock(com.family.finance.repository.PeriodMapper.class)));
    }

    /** 一行账户事实(orig==base · fx=1)。 */
    private AccountPeriodFact fact(long periodId, int month, String prevEnd, String end,
                                   String income, String expense) {
        LocalDate ps = LocalDate.of(2026, month, 1);
        BigDecimal prev = prevEnd == null ? null : new BigDecimal(prevEnd);
        BigDecimal e = new BigDecimal(end);
        BigDecimal inc = new BigDecimal(income), exp = new BigDecimal(expense);
        BigDecimal pnl = PnlCalculator.periodPnl(e, prev, inc, exp, Z, Z);
        return new AccountPeriodFact(
                1L, "acc1", AccountType.CASH, AccountClass.ASSET, AccountLiquidity.LIQUID, "CNY",
                null, 0, periodId, ps, ps,
                prev, e, prev, e,
                inc, inc, exp, exp,
                Z, Z, Z, Z,
                pnl, pnl, BigDecimal.ONE);
    }

    /**
     * 三期:6 月 1000 → 7 月 1200(收入 100)→ 8 月 1500(收支未录 = 0)。
     * 8 月那 +300 全是"还没录的收支",不该被当成投资收益。
     */
    private List<AccountPeriodFact> rows() {
        return List.of(
                fact(1L, 6, null, "1000", "0", "0"),
                fact(2L, 7, "1000", "1200", "100", "0"),
                fact(3L, 8, "1200", "1500", "0", "0"));
    }

    private FactSlice slice(List<Long> closed) {
        FactFilter f = new FactFilter(1L, PeriodType.MONTHLY,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 8, 1), false, null, "CNY");
        return new FactSlice(f, rows(), List.of(1L, 2L, 3L), 3L, closed);
    }

    /** 末期已关账 = 三期全参与(对照组)。 */
    private FactSlice allClosed() {
        return slice(List.of(1L, 2L, 3L));
    }

    /** 末期进行中 = 只有 6/7 月参与收益计算(被测组)。 */
    private FactSlice lastOpen() {
        return slice(List.of(1L, 2L));
    }

    @Test
    void 切片能正确区分已关账期与进行中期() {
        assertThat(allClosed().filingInProgress()).isFalse();
        assertThat(allClosed().returnAnchorPeriodId()).isEqualTo(3L);

        assertThat(lastOpen().filingInProgress()).isTrue();
        assertThat(lastOpen().returnPeriodIds()).containsExactly(1L, 2L);
        assertThat(lastOpen().returnAnchorPeriodId()).isEqualTo(2L);
    }

    @Test
    void 四参兼容构造把全部期视为已关账_保持v1_6_30之前行为() {
        FactFilter f = new FactFilter(1L, PeriodType.MONTHLY,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 8, 1), false, null, "CNY");
        FactSlice legacy = new FactSlice(f, rows(), List.of(1L, 2L, 3L), 3L);
        assertThat(legacy.closedPeriodIds()).containsExactly(1L, 2L, 3L);
        assertThat(legacy.filingInProgress()).isFalse();
        assertThat(legacy.returnAnchorPeriodId()).isEqualTo(3L);
    }

    @Test
    void 存量类KPI仍锚最后一期_不受关账状态影响() {
        // 填报中也要看到最新余额:净资产/总资产 两个切片必须一致
        assertThat(svc().kpis(lastOpen()).netWorth())
                .isEqualByComparingTo(svc().kpis(allClosed()).netWorth())
                .isEqualByComparingTo("1500");
    }

    @Test
    void 本月资产收益锚已关账期_不把未录收支算成投资收益() {
        KpiSnapshot open = svc().kpis(lastOpen());
        // 7 月:(1200 − 1000 − 100) ÷ 1000 = +10%
        assertThat(open.monthlyInvestReturnPct()).isEqualByComparingTo("0.100000");
        assertThat(open.monthlyPnlAmount()).isEqualByComparingTo("100");
        assertThat(open.returnAnchorNetWorth()).isEqualByComparingTo("1200");
        assertThat(open.returnAnchorMonth()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(open.filingInProgress()).isTrue();
        assertThat(open.returnPeriodCount()).isEqualTo(2);

        // 若错误地锚 8 月:(1500 − 1200 − 0) ÷ 1200 = +25% —— 那 300 是没录的收支,不是投资收益
        KpiSnapshot bad = svc().kpis(allClosed());
        assertThat(bad.monthlyInvestReturnPct()).isEqualByComparingTo("0.250000");
        assertThat(open.monthlyInvestReturnPct()).isNotEqualByComparingTo(bad.monthlyInvestReturnPct());
    }

    @Test
    void XIRR与TWR只用已关账期() {
        // 已关账 6→7:投入 1000 + 100 = 1100,回收 1200 → 累计 +9.09%
        assertThat(svc().familyXirr(lastOpen())).isEqualByComparingTo("0.09090909");
        // 含进行中 8 月会变成 投入 1100 回收 1500 → +36.36%(虚高)
        assertThat(svc().familyXirr(allClosed())).isEqualByComparingTo("0.36363636");

        assertThat(svc().familyTwr(lastOpen()))
                .isNotEqualByComparingTo(svc().familyTwr(allClosed()));
    }

    @Test
    void 人赚钱赚拆解只累计已关账期() {
        List<DecompositionPoint> open = svc().principalVsReturnDecomposition(lastOpen());
        assertThat(open).hasSize(1);
        assertThat(open.get(0).cumulativeNetInflow()).isEqualByComparingTo("100");
        assertThat(open.get(0).cumulativePnl()).isEqualByComparingTo("100");

        // 含进行中 8 月:那 +300 会整块落进「钱赚」(400),把未录工资算成投资收益
        List<DecompositionPoint> all = svc().principalVsReturnDecomposition(allClosed());
        assertThat(all).hasSize(2);
        assertThat(all.get(1).cumulativePnl()).isEqualByComparingTo("400");
    }

    @Test
    void 储蓄率走PMC优先并锚已关账期() {
        // PMC 未填 → 回落 cash_flow;锚 7 月:(100 − 0) ÷ 100 = 100%
        assertThat(svc().savingsRate(lastOpen())).isEqualByComparingTo("1.000000");
        // 8 月 cash_flow 收入为 0 —— 旧实现锚这里会返回 null → goals 页储蓄率目标进度恒为 0%
        assertThat(svc().savingsRate(allClosed())).isNull();
    }
}
