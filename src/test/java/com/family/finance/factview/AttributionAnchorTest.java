package com.family.finance.factview;

import com.family.finance.calc.PnlCalculator;
import com.family.finance.calc.review.AttributionEngine;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.18.3 · 归因复盘的两条不变量:<b>四项必须同期</b>,以及<b>当期转入不许变成假亏损</b>。
 *
 * <h3>这条测试的来历(两次反转,都记下来)</h3>
 * <p><b>v1.18.1</b>:生产上排行榜把一个只<b>收到一笔转入</b>的理财账户列成「亏得最多」,
 * 金额恰好等于那笔转入。当时的修法是把归因锚到「最新已关账期」—— 绕开进行中的月份。</p>
 *
 * <p><b>v1.18.3</b>:那是<b>权宜之计</b>,而且带来了新问题 —— 仪表盘上面的卡是本月、
 * 下面的归因瀑布是上月,同一屏两个月份,维护者拿本月印象去对上月的数,当场看成 bug。
 * 仪表盘的分工本来就是「当月实时」(v1.10 FR-327 已定)。</p>
 *
 * <p>能锚回当月,是因为<b>真正的病根已经修掉了</b>:假亏损不是「进行中的期」造成的,
 * 是 v1.18.1 后半段修的<b>丢钱 bug</b> —— 钱没落进现金行,被估值按「持仓合计」重算时抹掉,
 * 于是余额没涨、转入却记着,pnl = Δ余额(0) − 转入 = −转入。
 * 现在流水会立刻同步进余额,同一笔转入的 pnl 就是 0。<b>本测把这条前提钉死</b>:
 * 它一旦不成立,锚回当月就不再安全。</p>
 *
 * <p>金额用合成值,不搬生产真实数值(护栏 v111-NO-PROD-AMOUNTS)。</p>
 */
class AttributionAnchorTest {

    private static final BigDecimal Z = BigDecimal.ZERO;
    private static final String TRANSFER_IN = "30000";

    private FactViewServiceImpl svc() {
        AccountMapper am = mock(AccountMapper.class);
        when(am.findAllByFamily(anyLong())).thenReturn(List.of());
        PeriodMemberCashflowMapper pmc = mock(PeriodMemberCashflowMapper.class);
        when(pmc.findFamilyAggregateForPeriod(anyLong())).thenReturn(Optional.empty());
        SnapshotMapper sm = mock(SnapshotMapper.class);
        when(sm.firstAppearingAccountIds(anyLong(), anyLong())).thenReturn(List.of());
        return new FactViewServiceImpl(mock(FactMapper.class), mock(FamilyMapper.class),
                pmc, am, mock(ProductCategoryService.class), sm,
                mock(com.family.finance.repository.PeriodAccountAttrMapper.class),
                new com.family.finance.service.expense.ExpenseLedgerService(
                        mock(com.family.finance.repository.CashFlowMapper.class), pmc,
                        mock(FamilyMapper.class),
                        mock(com.family.finance.repository.PeriodMapper.class)));
    }

    /** 一行账户事实(orig == base · fx = 1)。 */
    private AccountPeriodFact fact(long periodId, int month, String prevEnd, String end,
                                   String income, String expense, String tin, String tout) {
        LocalDate ps = LocalDate.of(2026, month, 1);
        BigDecimal prev = prevEnd == null ? null : new BigDecimal(prevEnd);
        BigDecimal e = new BigDecimal(end);
        BigDecimal inc = new BigDecimal(income), exp = new BigDecimal(expense);
        BigDecimal ti = new BigDecimal(tin), to = new BigDecimal(tout);
        BigDecimal pnl = PnlCalculator.periodPnl(e, prev, inc, exp, ti, to);
        return new AccountPeriodFact(
                7L, "理财-货币基金", AccountType.WEALTH, AccountClass.ASSET, AccountLiquidity.LIQUID, "CNY",
                null, 0, periodId, ps, ps,
                prev, e, prev, e,
                inc, inc, exp, exp,
                ti, ti, to, to,
                pnl, pnl, BigDecimal.ONE);
    }

    // ────────────────────────────────────────────────────────────────
    // ① 锚回当月的前提:流水同步进余额,转入就不再是假亏损
    // ────────────────────────────────────────────────────────────────

    /**
     * <b>v1.18.1 之前的形态</b>(留作反面教材):钱进来了、余额没动 → 整笔转入被读成亏损。
     * 这不是「进行中的期」的锅,是钱被估值抹掉了。
     */
    @Test
    void 余额没跟上时_转入会被读成同额亏损_这是丢钱bug的表征() {
        AccountPeriodFact broken = fact(3L, 7, "200000", "200000", "0", "0", TRANSFER_IN, "0");
        assertThat(broken.periodPnlBase()).isEqualByComparingTo("-" + TRANSFER_IN);
    }

    /**
     * <b>v1.18.1 之后的形态</b>:划转会立刻把钱加进余额(以及托管账户的现金行),
     * 于是同一笔转入的 pnl 就是 0 —— <b>这条成立,锚回当月才安全</b>。
     */
    @Test
    void 余额同步更新后_同一笔转入的损益是零() {
        AccountPeriodFact fixed = fact(3L, 7, "200000", "230000", "0", "0", TRANSFER_IN, "0");
        assertThat(fixed.periodPnlBase()).isEqualByComparingTo("0");

        AttributionEngine.Result r = AttributionEngine.attribute(inputs(List.of(fixed)), Z, Z, Z);
        assertThat(r.slices()).as("零贡献不该进排行榜,更不该出现在「亏得最多」里").isEmpty();
    }

    /** 转账不进收入侧 —— 当初的猜测是「转账被计入收入」,这条钉住实际口径。 */
    @Test
    void 转账不进收入侧_收入只读cash_flow() {
        AccountPeriodFact f = fact(3L, 7, "200000", "230000", "0", "0", TRANSFER_IN, "0");
        assertThat(f.incomeOrig()).isEqualByComparingTo(Z);
        assertThat(f.incomeBase()).isEqualByComparingTo(Z);
        // pnl 公式里转账是被【减掉】的 —— 所以余额没跟上时它变成负数,而不是变成"收入"
        assertThat(PnlCalculator.periodPnl(new BigDecimal("200000"), new BigDecimal("200000"),
                Z, Z, new BigDecimal(TRANSFER_IN), Z)).isEqualByComparingTo("-" + TRANSFER_IN);
    }

    // ────────────────────────────────────────────────────────────────
    // ② 不因锚点变化而改变的那条:四项必须同期
    // ────────────────────────────────────────────────────────────────

    private List<AccountPeriodFact> rows() {
        return List.of(
                fact(1L, 5, null, "190000", "0", "0", "0", "0"),
                fact(2L, 6, "190000", "200000", "0", "0", "9000", "0"),
                fact(3L, 7, "200000", "230000", "0", "0", TRANSFER_IN, "0"));
    }

    private FactSlice slice(List<Long> closed) {
        FactFilter f = new FactFilter(1L, PeriodType.MONTHLY,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 7, 1), false, null, "CNY");
        return new FactSlice(f, rows(), List.of(1L, 2L, 3L), 3L, closed);
    }

    /** 仪表盘归因锚【用户正在看的这一期】= lastPeriodId(默认当月,可能进行中)。 */
    @Test
    void 仪表盘归因锚当月_而收益类KPI仍锚已关账期() {
        FactSlice s = slice(List.of(1L, 2L));          // 7 月进行中
        assertThat(s.lastPeriodId()).as("归因/存量看这一期").isEqualTo(3L);
        assertThat(s.returnAnchorPeriodId()).as("本月资产收益等收益类 KPI 仍锚已关账期").isEqualTo(2L);
        assertThat(s.filingInProgress()).as("页面据此提示「实时口径 + 收支可能没录齐」").isTrue();
    }

    /**
     * <b>混锚会把差额藏进「未归因」</b> —— 瀑布靠 ΔNW = 人赚 + 钱赚 + 开账基线 + 未归因 闭合,
     * 而「未归因」是<b>残差定义</b>,按构造恒等成立。四项不同期时,差额会被它悄悄吸收:
     * 页面看着平了,错误其实藏进了兜底项。这条与锚在哪一期无关,永远成立。
     */
    @Test
    void 混锚会把差额藏进未归因() {
        FactSlice s = slice(List.of(1L, 2L));
        KpiSnapshot k = svc().kpis(s);
        List<AccountPeriodFact> liveRows = s.byPeriod().get(s.lastPeriodId());

        // 同期:ΔNW 与 slices 都取当月
        AttributionEngine.Result aligned = AttributionEngine.attribute(
                inputs(liveRows), k.netWorthDelta(), Z, k.openingBaselineLast());
        assertThat(aligned.moneyEarnedTotal()).as("7 月余额同步更新 → 钱赚为 0").isEqualByComparingTo("0");
        assertThat(aligned.unattributed())
                .as("未归因 = ΔNW − 人赚(这里传 0)− 开账 − 钱赚,是可解释的余项")
                .isEqualByComparingTo(k.netWorthDelta());

        // 混锚:slices 取当月、ΔNW 却取上一期 → 差额被「未归因」吃掉,页面照样"闭合"
        BigDecimal wrongDelta = new BigDecimal("10000");   // 6 月的 ΔNW
        AttributionEngine.Result mixed = AttributionEngine.attribute(inputs(liveRows), wrongDelta, Z, Z);
        assertThat(mixed.unattributed()).isEqualByComparingTo(wrongDelta);
        assertThat(mixed.unattributed()).as("差额被吸收,数字看着平了 —— 这正是危险之处")
                .isNotEqualByComparingTo(aligned.unattributed());
    }

    private List<AttributionEngine.AcctInput> inputs(List<AccountPeriodFact> rows) {
        return rows.stream().map(f -> new AttributionEngine.AcctInput(
                f.accountId(), f.accountName(), f.accountCurrency(),
                f.periodPnlBase(), f.periodPnlOrig(),
                f.endBalanceBase(), f.endBalanceOrig(),
                f.previousEndBalanceBase(), f.previousEndBalanceOrig(),
                Map.<String, String>of())).toList();
    }
}
