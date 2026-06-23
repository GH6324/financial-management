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
import com.family.finance.service.ProductCategoryService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v0.8 护栏(v08-CCY-INV)· <b>币种不变性属性测试</b>。
 *
 * <p>反复出过的「切币种指标乱漂」(本月资产收益率 CNY −18% / HKD −9% / USD −88%)本质是
 * <b>把视图币种当成显示镜头时,跨期/跨账户的换算口径不一致</b>。这道护栏把它当成一条<b>不变式</b>来钉:</p>
 *
 * <ul>
 *   <li><b>比值类指标(收益率/紧急储备月数/负债率/净资产环比%)→ 三种视图币种必须完全相等</b>;</li>
 *   <li><b>金额类指标(净资产/总资产/总负债/本月收益额/月均支出)→ 必须按汇率因子精确缩放</b>。</li>
 * </ul>
 *
 * <p>做法:同一套 orig(本位币 CNY)经济事实,分别以 k=1(CNY 视图)/ k=6.774(USD→CNY 反向,模拟某外币视图)/
 * k=0.14761(另一外币视图)构造 view 切片(所有 base 字段 ×k、fxToBase=k),跑 {@code kpis},断言上述不变式。
 * 任何未来新增的比值指标,只要进了这套断言就自动被网住。计算口径退化会在这里立刻红。</p>
 */
class CurrencyInvarianceTest {

    private static final BigDecimal Z = BigDecimal.ZERO;

    private FactViewServiceImpl svc() {
        FactMapper fm = mock(FactMapper.class);                     // kpis 内 ytd 独立加载 → 空,不影响本测
        FamilyMapper famMapper = mock(FamilyMapper.class);
        Family fam = mock(Family.class);
        when(fam.getBaseCurrency()).thenReturn("CNY");
        when(famMapper.findById(anyLong())).thenReturn(Optional.of(fam));
        PeriodMemberCashflowMapper pmc = mock(PeriodMemberCashflowMapper.class);
        when(pmc.findFamilyAggregateForPeriod(anyLong())).thenReturn(Optional.empty()); // 走 cash_flow 净流入回退
        AccountMapper am = mock(AccountMapper.class);
        when(am.findById(anyLong())).thenReturn(Optional.empty());
        return new FactViewServiceImpl(fm, famMapper, pmc, am, mock(ProductCategoryService.class));
    }

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    /** 一行账户事实:orig 给定,base 字段 = orig×k,fxToBase=k(视图币种镜头)。periodPnl 走真实公式。 */
    private AccountPeriodFact row(long accId, AccountType type, AccountClass cls, AccountLiquidity liq,
                                  long periodId, int month, String prevOrig, String endOrig,
                                  String incOrig, String expOrig, BigDecimal k) {
        LocalDate ps = LocalDate.of(2025, month, 1);
        BigDecimal prev = prevOrig == null ? null : bd(prevOrig);
        BigDecimal end = bd(endOrig);
        BigDecimal inc = bd(incOrig), exp = bd(expOrig);
        BigDecimal pnl = PnlCalculator.periodPnl(end, prev, inc, exp, Z, Z);   // 首期 prev=null → pnl=null
        return new AccountPeriodFact(
                accId, "acc" + accId, type, cls, liq, "CNY",
                null, 0, periodId, ps, ps,
                prev, end, prev == null ? null : prev.multiply(k), end.multiply(k),
                inc, inc.multiply(k), exp, exp.multiply(k),
                Z, Z, Z, Z,
                pnl, pnl == null ? null : pnl.multiply(k), k);
    }

    /** 同一套经济事实,以视图因子 k 构造切片(view 串:k=1→CNY 本位币;否则外币视图)。 */
    private FactSlice sliceFor(BigDecimal k, String view) {
        List<AccountPeriodFact> rows = List.of(
                // 流动现金资产:P1 100000 → P2 110000(其中 income 8000 / expense 3000 → 纯投资损益 5000)
                row(1, AccountType.CASH, AccountClass.ASSET, AccountLiquidity.LIQUID, 101, 1, null, "100000", "0", "0", k),
                row(1, AccountType.CASH, AccountClass.ASSET, AccountLiquidity.LIQUID, 102, 2, "100000", "110000", "8000", "3000", k),
                // 负债(贷款):P1 -50000 → P2 -48000
                row(2, AccountType.LOAN, AccountClass.LIABILITY, AccountLiquidity.LIQUID, 101, 1, null, "-50000", "0", "0", k),
                row(2, AccountType.LOAN, AccountClass.LIABILITY, AccountLiquidity.LIQUID, 102, 2, "-50000", "-48000", "0", "0", k));
        FactFilter f = new FactFilter(1L, PeriodType.MONTHLY,
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 28), false, null, view);
        return new FactSlice(f, rows, List.of(101L, 102L), 102L);
    }

    private KpiSnapshot kpisFor(BigDecimal k, String view) {
        return svc().kpis(sliceFor(k, view));
    }

    @Test
    void ratioMetrics_areCurrencyInvariant_andAmounts_scaleByFxFactor() {
        BigDecimal kCny = BigDecimal.ONE;
        BigDecimal kUsd = bd("6.774");     // 模拟 USD→CNY 反向因子(1 USD = 6.774 CNY)
        BigDecimal kHkd = bd("0.147610");  // 模拟某外币视图因子

        KpiSnapshot cny = kpisFor(kCny, "CNY");
        KpiSnapshot usd = kpisFor(kUsd, "USD");
        KpiSnapshot hkd = kpisFor(kHkd, "HKD");

        // 前置健全性:CNY(本位币)视图各比值确为期望值
        assertThat(cny.debtToAssetRatio()).isEqualByComparingTo(bd("48000").divide(bd("110000"), 6, RoundingMode.HALF_EVEN));
        assertThat(cny.netWorthDeltaPct()).isEqualByComparingTo(bd("12000").divide(bd("50000"), 6, RoundingMode.HALF_EVEN));
        // 本月投资收益率 = (62000 − 50000 − 5000)/50000 = 7000/50000 = 0.14
        assertThat(cny.monthlyInvestReturnPct()).isEqualByComparingTo(bd("0.14000000"));
        assertThat(cny.emergencyFundMonths()).isNotNull();

        // ① 比值类:三视图币种必须完全相等(这是「币种无关」的核心不变式)
        for (KpiSnapshot v : List.of(usd, hkd)) {
            assertThat(v.debtToAssetRatio()).as("负债率币种无关").isEqualByComparingTo(cny.debtToAssetRatio());
            assertThat(v.netWorthDeltaPct()).as("净资产环比%币种无关").isEqualByComparingTo(cny.netWorthDeltaPct());
            assertThat(v.monthlyInvestReturnPct()).as("本月资产收益率币种无关").isEqualByComparingTo(cny.monthlyInvestReturnPct());
            assertThat(v.emergencyFundMonths()).as("紧急储备月数币种无关").isEqualByComparingTo(cny.emergencyFundMonths());
        }

        // ② 金额类:必须按汇率因子精确缩放
        assertThat(usd.netWorth()).as("净资产按因子缩放")
                .isEqualByComparingTo(cny.netWorth().multiply(kUsd).setScale(2, RoundingMode.HALF_EVEN));
        assertThat(hkd.netWorth()).isEqualByComparingTo(cny.netWorth().multiply(kHkd).setScale(2, RoundingMode.HALF_EVEN));
        assertThat(usd.totalAssets()).isEqualByComparingTo(cny.totalAssets().multiply(kUsd).setScale(2, RoundingMode.HALF_EVEN));
        assertThat(usd.totalLiabilities()).isEqualByComparingTo(cny.totalLiabilities().multiply(kUsd).setScale(2, RoundingMode.HALF_EVEN));
        assertThat(usd.monthlyPnlAmount()).as("本月收益额按因子缩放")
                .isEqualByComparingTo(cny.monthlyPnlAmount().multiply(kUsd).setScale(2, RoundingMode.HALF_EVEN));
        assertThat(usd.avgExpense()).as("月均支出按因子缩放")
                .isEqualByComparingTo(cny.avgExpense().multiply(kUsd).setScale(2, RoundingMode.HALF_EVEN));
    }

    @Test
    void accountPerformance_sharePct_isCurrencyInvariant_andCurrentValue_scales() {
        BigDecimal kUsd = bd("6.774");
        AccountPerformance cny1 = svc().accountPerformance(sliceFor(BigDecimal.ONE, "CNY")).stream()
                .filter(p -> p.accountId() == 1L).findFirst().orElseThrow();
        AccountPerformance usd1 = svc().accountPerformance(sliceFor(kUsd, "USD")).stream()
                .filter(p -> p.accountId() == 1L).findFirst().orElseThrow();

        // 占比是比值 → 币种无关
        assertThat(usd1.sharePct()).as("账户占比币种无关").isEqualByComparingTo(cny1.sharePct());
        // 现值是金额 → 按因子缩放
        assertThat(usd1.currentValue()).as("账户现值按因子缩放")
                .isEqualByComparingTo(cny1.currentValue().multiply(kUsd).setScale(2, RoundingMode.HALF_EVEN));
    }
}
