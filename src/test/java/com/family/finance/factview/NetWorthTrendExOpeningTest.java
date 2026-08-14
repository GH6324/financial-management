package com.family.finance.factview;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.9.4 · {@code netWorthTrendExOpening}(财富水位专用序列)的首点语义。
 *
 * <p><b>为什么有这个测试</b>:prod 实报「关了三期还在显示财富水位需要至少 2 期」。
 * 根因是这条序列每期都减掉「本期首次出现账户的期末净值」——**包括窗口首期**,
 * 而首期的「首次出现账户」按定义就是全部账户,于是首点恒等于 0。
 * {@code WaterLevelService} 以首点为锚、anchor<=0 判不可用 →
 * 只要时间窗包含家庭首期,财富水位就永久不出现。新用户只有两三期、任何窗口都含首期,
 * 所以这一节对他们从来没出现过。</p>
 *
 * <p>{@code ReportsController} 在 v1.6.29 的注释里已经记下过「该序列首点按构造恒为 0」,
 * 但当时只把 tooltip 那个消费方换成了 {@code netWorthTrend},
 * 财富水位这个**主**消费方留在了坏序列上 —— 所以这里用测试把首点语义钉住。</p>
 */
class NetWorthTrendExOpeningTest {

    private static final BigDecimal Z = BigDecimal.ZERO;

    /** 每期首次出现的账户 id;key = periodId。 */
    private FactViewServiceImpl svc(java.util.Map<Long, List<Long>> firstAppearing) {
        AccountMapper am = mock(AccountMapper.class);
        when(am.findAllByFamily(anyLong())).thenReturn(java.util.List.of());   // v1.12 · 预实改家庭级批量取账户(原逐个 findById)· 空 → expected null
        SnapshotMapper sm = mock(SnapshotMapper.class);
        when(sm.firstAppearingAccountIds(anyLong(), anyLong()))
                .thenAnswer(inv -> firstAppearing.getOrDefault(inv.getArgument(1, Long.class), List.of()));
        return new FactViewServiceImpl(mock(FactMapper.class), mock(FamilyMapper.class),
                mock(PeriodMemberCashflowMapper.class), am, mock(ProductCategoryService.class), sm,
                mock(com.family.finance.repository.PeriodAccountAttrMapper.class),
                new com.family.finance.service.expense.ExpenseLedgerService(
                        mock(com.family.finance.repository.CashFlowMapper.class),
                        mock(PeriodMemberCashflowMapper.class),
                        mock(FamilyMapper.class),
                        mock(com.family.finance.repository.PeriodMapper.class)));
    }

    private AccountPeriodFact row(long accId, long periodId, int month, String endBase) {
        LocalDate ps = LocalDate.of(2026, month, 1);
        return new AccountPeriodFact(accId, "acc" + accId, AccountType.CASH, AccountClass.ASSET,
                AccountLiquidity.LIQUID, "CNY", 1L, 1, periodId, ps, ps.plusMonths(1).minusDays(1),
                null, new BigDecimal(endBase), null, new BigDecimal(endBase),
                Z, Z, Z, Z, Z, Z, Z, Z, null, null, BigDecimal.ONE);
    }

    private FactSlice slice(List<AccountPeriodFact> rows, List<Long> periodIds) {
        return new FactSlice(new FactFilter(1L, PeriodType.MONTHLY,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), false, null, "CNY"),
                rows, periodIds, periodIds.getLast());
    }

    @Test
    void 窗口首期的首点必须是真实净资产_不能恒为0() {
        // 家庭首期:两个账户都是「首次出现」,合计 100 万。
        // 老实现:100万 − 100万 = 0 → 财富水位永久不可用。
        var rows = new ArrayList<AccountPeriodFact>();
        rows.add(row(1, 101, 1, "600000"));
        rows.add(row(2, 101, 1, "400000"));
        rows.add(row(1, 102, 2, "650000"));
        rows.add(row(2, 102, 2, "420000"));

        var trend = svc(java.util.Map.of(101L, List.of(1L, 2L)))
                .netWorthTrendExOpening(slice(rows, List.of(101L, 102L)));

        assertThat(trend).hasSize(2);
        assertThat(trend.get(0).value()).isEqualByComparingTo("1000000");   // 曾经是 0
        assertThat(trend.get(1).value()).isEqualByComparingTo("1070000");
    }

    @Test
    void 第二期起新出现的账户仍然要剔除() {
        // 起跑 100 万;第 2 期补录一个本来就有的 50 万存量账户 → 那 50 万是外部资本纳入,
        // 不算增值,必须从曲线里剔掉,否则财富水位会显示「跑赢 CPI」的假象。
        var rows = new ArrayList<AccountPeriodFact>();
        rows.add(row(1, 101, 1, "1000000"));
        rows.add(row(1, 102, 2, "1010000"));
        rows.add(row(3, 102, 2, "500000"));          // 新账户,期末 50 万
        rows.add(row(1, 103, 3, "1020000"));
        rows.add(row(3, 103, 3, "505000"));

        var trend = svc(java.util.Map.of(101L, List.of(1L), 102L, List.of(3L)))
                .netWorthTrendExOpening(slice(rows, List.of(101L, 102L, 103L)));

        assertThat(trend.get(0).value()).isEqualByComparingTo("1000000");
        assertThat(trend.get(1).value()).isEqualByComparingTo("1010000");   // 1010000+500000−500000
        assertThat(trend.get(2).value()).isEqualByComparingTo("1025000");   // 1020000+505000−500000
    }

    @Test
    void 不含首期的窗口逐点不变_零差异() {
        // 3M/6M/YTD/1Y 这类窗口里没有任何账户「首次出现」→ 本方法必须等于净资产本身。
        // 这条钉住修复的兼容性:已经能用的窗口一个数字都不许动。
        var rows = List.of(row(1, 201, 4, "2000000"), row(1, 202, 5, "2100000"));
        var trend = svc(java.util.Map.of()).netWorthTrendExOpening(slice(rows, List.of(201L, 202L)));
        assertThat(trend.get(0).value()).isEqualByComparingTo("2000000");
        assertThat(trend.get(1).value()).isEqualByComparingTo("2100000");
    }
}
