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
 * v1.18.1 · 归因复盘(谁赚得多 / 谁亏得多)必须锚「最新已关账期」。
 *
 * <p><b>起因是一次真实误判</b>:生产上排行榜把一个只<b>收到一笔转入</b>的理财账户列成
 * 「亏得最多」,金额恰好等于那笔转入的全额。用户去翻流水才发现「这个账户根本没亏损」。</p>
 *
 * <p>机制不是「转账被算成了收入」—— 转账在 {@link PnlCalculator#periodPnl} 里是被<b>减掉</b>的,
 * 收入侧读的是 {@code cash_flow.INCOME},压根不含 transfer。真正的原因是<b>锚期错了</b>:
 * 归因原来锚 {@code lastPeriodId},而进行中的那一期典型状态是
 * <b>「转账已登记、月末余额还没填」</b>(余额是开账时延续来的旧值),于是</p>
 *
 * <pre>  pnl = Δ余额(0) − 收支(0) − 净转入(+X) = −X</pre>
 *
 * <p>—— 一笔转入被原封不动地读成了同额亏损。v1.6.30 已经为「本月资产收益」立过同一条规矩
 * (收益类锚已关账期),归因这条当时漏了。</p>
 *
 * <p>还有一个陷阱:归因瀑布靠恒等式 ΔNW = 人赚 + 钱赚 + 开账基线 + 未归因 闭合,
 * 四项必须<b>同一期</b>。只把「钱赚」挪到已关账期、ΔNW 还留在最后一期,差额会全被
 * 「未归因」吸收 —— 数字看着平了,其实是把错误藏进了兜底项。所以本测同时钉住
 * {@code returnAnchorDelta} / {@code returnAnchorOpeningBaseline} 这两个同期字段存在且自洽。</p>
 *
 * <p>金额一律用合成的整数,不搬生产真实数值(护栏 v111-NO-PROD-AMOUNTS)。</p>
 */
class AttributionAnchorTest {

    private static final BigDecimal Z = BigDecimal.ZERO;

    /** 期末余额:两期都是 200000(= 进行中那期还没填,是开账延续来的旧值) */
    private static final String STALE_BALANCE = "200000";
    /** 进行中那期收到的转入 */
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

    /**
     * 三期,同一个账户 —— 三期是必需的:归因锚期自己也要有「上一期」才算得出 ΔNW,
     * 只造两期会让锚期落在窗口第一期上、{@code returnAnchorDelta} 恒为 null
     * (第一版就是这么写的,测试当场红,而那是测试数据的问题不是实现的问题)。
     * <ul>
     *   <li>5 月(已关账):建仓 190000</li>
     *   <li>6 月(已关账):190000 → 200000,收到转入 9000 → 真实损益 +1000</li>
     *   <li>7 月(进行中):余额<b>没动</b>(还是 200000 · 开账延续值),收到转入 30000</li>
     * </ul>
     */
    private List<AccountPeriodFact> rows() {
        return List.of(
                fact(1L, 5, null, "190000", "0", "0", "0", "0"),
                fact(2L, 6, "190000", STALE_BALANCE, "0", "0", "9000", "0"),
                fact(3L, 7, STALE_BALANCE, STALE_BALANCE, "0", "0", TRANSFER_IN, "0"));
    }

    private FactSlice slice(List<Long> closed) {
        FactFilter f = new FactFilter(1L, PeriodType.MONTHLY,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 7, 1), false, null, "CNY");
        return new FactSlice(f, rows(), List.of(1L, 2L, 3L), 3L, closed);
    }

    /** 末期进行中(生产上出问题的形态) */
    private FactSlice lastOpen() { return slice(List.of(1L, 2L)); }

    /** 对照:末期也已关账 */
    private FactSlice allClosed() { return slice(List.of(1L, 2L, 3L)); }

    // ────────────────────────────────────────────────────────────────
    // ① 先钉住"病根"本身:进行中那期的 pnl 就是负的转入额
    //    这条不是在测 bug,是把「为什么不能拿进行中的期算归因」写成可执行的证据。
    //    以后有人想把锚改回 lastPeriodId,先得解释这个数。
    // ────────────────────────────────────────────────────────────────
    @Test
    void 进行中那期的损益等于负的转入额_这就是假亏损的来源() {
        AccountPeriodFact open = rows().get(2);
        assertThat(open.periodPnlBase()).isEqualByComparingTo("-" + TRANSFER_IN);
        // 余额一分没动、收支一笔没录 —— 唯一的输入就是那笔转入
        assertThat(open.endBalanceOrig()).isEqualByComparingTo(open.previousEndBalanceOrig());
        assertThat(open.incomeOrig()).isEqualByComparingTo(Z);
        assertThat(open.transferInOrig()).isEqualByComparingTo(TRANSFER_IN);
    }

    /** 转账不在收入侧 —— 用户当时的猜测是「转账被计入收入」,这条钉住实际口径。 */
    @Test
    void 转账不进收入侧_收入只读cash_flow() {
        AccountPeriodFact open = rows().get(2);
        assertThat(open.incomeOrig()).isEqualByComparingTo(Z);
        assertThat(open.incomeBase()).isEqualByComparingTo(Z);
        // 而 pnl 公式里转账是被减掉的(所以它才会变成负数,而不是变成"收入")
        assertThat(PnlCalculator.periodPnl(new BigDecimal(STALE_BALANCE), new BigDecimal(STALE_BALANCE),
                Z, Z, new BigDecimal(TRANSFER_IN), Z)).isEqualByComparingTo("-" + TRANSFER_IN);
    }

    // ────────────────────────────────────────────────────────────────
    // ② 锚点:归因必须落在已关账期
    // ────────────────────────────────────────────────────────────────
    @Test
    void 归因锚点是最新已关账期_不是最后一期() {
        FactSlice s = lastOpen();
        assertThat(s.lastPeriodId()).isEqualTo(3L);              // 存量类看这一期(余额要最新)
        assertThat(s.returnAnchorPeriodId()).isEqualTo(2L);      // 收益类(含归因)看这一期
        assertThat(s.filingInProgress()).isTrue();               // 页面据此提示口径

        assertThat(allClosed().returnAnchorPeriodId()).isEqualTo(3L);
        assertThat(allClosed().filingInProgress()).isFalse();
    }

    @Test
    void 锚已关账期后假亏损消失_剩下真实损益() {
        FactSlice s = lastOpen();
        List<AccountPeriodFact> anchorRows = s.byPeriod().get(s.returnAnchorPeriodId());
        AttributionEngine.Result r = AttributionEngine.attribute(inputs(anchorRows), Z, Z, Z);
        assertThat(r.slices()).hasSize(1);
        // 6 月真实损益 = (200000 − 190000) − 9000 = +1000
        assertThat(r.slices().get(0).pnlBase()).isEqualByComparingTo("1000");
        assertThat(r.moneyEarnedTotal()).isEqualByComparingTo("1000");

        // 对照:锚最后一期(改动前的行为)会得到 −30000 的假亏损
        List<AccountPeriodFact> openRows = s.byPeriod().get(s.lastPeriodId());
        AttributionEngine.Result bug = AttributionEngine.attribute(inputs(openRows), Z, Z, Z);
        assertThat(bug.slices().get(0).pnlBase()).isEqualByComparingTo("-" + TRANSFER_IN);
    }

    // ────────────────────────────────────────────────────────────────
    // ③ 恒等式四项必须同期 —— 只挪一项会把差额藏进「未归因」
    // ────────────────────────────────────────────────────────────────
    @Test
    void kpi暴露归因同期的ΔNW与开账基线() {
        KpiSnapshot k = svc().kpis(lastOpen());
        // 存量口径仍锚最后一期:余额没动,所以 ΔNW = 0
        assertThat(k.netWorthDelta()).isEqualByComparingTo("0");
        // 归因口径锚 6 月:ΔNW = 200000 − 190000 = 10000
        assertThat(k.returnAnchorDelta()).isEqualByComparingTo("10000");
        // 本测把「首次出现账户」置空 → 开账基线恒 0,但字段必须存在(不能是 null 让调用方 NPE)
        assertThat(k.returnAnchorOpeningBaseline()).isNotNull().isEqualByComparingTo("0");
    }

    /**
     * 混锚就是这次差点犯的错:钱赚用 6 月、ΔNW 用 7 月 → 差额被「未归因」吞掉,
     * 页面看着还是闭合的,而错误已经藏进兜底项里。
     */
    @Test
    void 混锚会把差额藏进未归因() {
        FactSlice s = lastOpen();
        KpiSnapshot k = svc().kpis(s);
        List<AccountPeriodFact> anchorRows = s.byPeriod().get(s.returnAnchorPeriodId());

        AttributionEngine.Result mixed = AttributionEngine.attribute(
                inputs(anchorRows), k.netWorthDelta(), Z, Z);          // ΔNW 用了最后一期
        assertThat(mixed.unattributed()).isEqualByComparingTo("-1000");   // 差额被吸收

        AttributionEngine.Result aligned = AttributionEngine.attribute(
                inputs(anchorRows), k.returnAnchorDelta(), Z, k.returnAnchorOpeningBaseline());
        assertThat(aligned.unattributed()).isEqualByComparingTo("9000");  // = 那期的人赚/净转入,由调用方补上
        // 关键:同期时「未归因」是可解释的余项,不是被吞掉的口径差
        assertThat(aligned.moneyEarnedTotal()).isEqualByComparingTo("1000");
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
