package com.family.finance.factview;

import com.family.finance.domain.account.AccountClass;
import com.family.finance.domain.account.AccountLiquidity;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.period.PeriodType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.18.7 · 仪表盘自称「<b>实时汇总</b>」,那么它上面的每个数就得说清自己是哪一期。
 *
 * <h3>这一版在收的口子</h3>
 * <p>2026-08-25 逐项 review 仪表盘,发现三类「口径混在一屏、页面上看不出来」的问题。
 * 页面上已经有<b>三个做对了的标杆</b>,它们是这条测试的参照:</p>
 * <ul>
 *   <li>「本月资产收益」—— live 口径 + 「本月未封板 · 已录收入 X / 支出 Y」+ 一笔没录时直说不宜作判断</li>
 *   <li>「本期怎么变的」—— 标题挂「2026-08 · <b>进行中</b>」</li>
 *   <li>「收支趋势 · 实时」—— 「含进行中的本月(最右浅色)· 报表的收支趋势只到上一已关账期」</li>
 * </ul>
 *
 * <p>本测钉的是<b>净资产趋势</b>那一项:它的最右点同样是进行中期,却和已关账的点长得一模一样。
 * 净资产是<b>存量</b>,月中快照本身是曲线上合法的点(不像流量那样只录了半个月),所以危害比收支小 ——
 * 但「这个点还会变」该让人看见,而不是靠他自己记得今天几号。</p>
 */
class DashboardLiveScopeTest {

    private static AccountPeriodFact fact(long periodId, int month, String end) {
        LocalDate ps = LocalDate.of(2026, month, 1);
        BigDecimal e = new BigDecimal(end);
        BigDecimal z = BigDecimal.ZERO;
        return new AccountPeriodFact(
                7L, "理财-货币基金", AccountType.WEALTH, AccountClass.ASSET, AccountLiquidity.LIQUID, "CNY",
                null, 0, periodId, ps, ps,
                null, e, null, e,
                z, z, z, z, z, z, z, z,
                z, z, BigDecimal.ONE);
    }

    private static FactSlice slice(List<Long> closed) {
        FactFilter f = new FactFilter(1L, PeriodType.MONTHLY,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 8, 1), false, null, "CNY");
        return new FactSlice(f,
                List.of(fact(1L, 6, "100000"), fact(2L, 7, "110000"), fact(3L, 8, "120000")),
                List.of(1L, 2L, 3L), 3L, closed);
    }

    private FactViewServiceImpl svc() {
        return new FactViewServiceImpl(
                org.mockito.Mockito.mock(com.family.finance.repository.FactMapper.class),
                org.mockito.Mockito.mock(com.family.finance.repository.FamilyMapper.class),
                org.mockito.Mockito.mock(com.family.finance.repository.PeriodMemberCashflowMapper.class),
                org.mockito.Mockito.mock(com.family.finance.repository.AccountMapper.class),
                org.mockito.Mockito.mock(com.family.finance.service.ProductCategoryService.class),
                org.mockito.Mockito.mock(com.family.finance.repository.SnapshotMapper.class),
                org.mockito.Mockito.mock(com.family.finance.repository.PeriodAccountAttrMapper.class),
                org.mockito.Mockito.mock(com.family.finance.service.expense.ExpenseLedgerService.class));
    }

    /** 8 月还没关账 → 趋势的最后一个点必须自报「我还在动」。 */
    @Test
    void 净资产趋势要标出进行中的那一期() {
        List<TrendPoint> pts = svc().netWorthTrend(slice(List.of(1L, 2L)));
        assertThat(pts).hasSize(3);
        assertThat(pts.get(0).live()).as("6 月已关账").isFalse();
        assertThat(pts.get(1).live()).as("7 月已关账").isFalse();
        assertThat(pts.get(2).live())
                .as("8 月进行中 —— 这个点还会变,图上不该和已定格的点长得一样").isTrue();
    }

    /** 全部已关账 → 一个 live 都没有,不许平白给最后一个点扣上「进行中」的帽子。 */
    @Test
    void 全部关账时没有进行中标记() {
        List<TrendPoint> pts = svc().netWorthTrend(slice(List.of(1L, 2L, 3L)));
        assertThat(pts).allMatch(p -> !p.live());
    }

    /**
     * 拿不到关账信息时(4 参构造 / 老调用方)<b>一律不标</b>。
     * 宁可不标,也不能靠「最后一个点大概是进行中的」去猜 —— 猜错就是给已定格的数字打上「还会变」。
     */
    @Test
    void 没有关账信息时不猜() {
        FactFilter f = new FactFilter(1L, PeriodType.MONTHLY,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 8, 1), false, null, "CNY");
        FactSlice s = new FactSlice(f,
                List.of(fact(1L, 6, "100000"), fact(2L, 7, "110000")), List.of(1L, 2L), 2L);
        assertThat(svc().netWorthTrend(s)).allMatch(p -> !p.live());
    }

    /** 向后兼容:4 参构造出来的点默认 live=false,老调用方一个字不用改。 */
    @Test
    void TrendPoint四参构造默认不是进行中() {
        assertThat(new TrendPoint(1L, LocalDate.of(2026, 6, 1), "2026-06", BigDecimal.TEN).live()).isFalse();
    }
}
