package com.family.finance.service.expense;

import com.family.finance.domain.family.Family;
import com.family.finance.domain.period.Period;
import com.family.finance.repository.CashFlowMapper;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper.FamilyPeriodAggregate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.18.7 · 「月均支出」不许把<b>半个月</b>当整月。
 *
 * <h3>这条测试在拦什么</h3>
 * <p>月中打开页面时,进行中账期的支出只录了一部分,却按<b>一整月</b>参与 12 期均值 →
 * 月均支出偏低。它是<b>分母</b>,所以后果是连锁的:</p>
 * <ul>
 *   <li>紧急储备 = 流动资产 ÷ 月均支出 → <b>虚高</b></li>
 *   <li>「应急金充足 + 超额闲置」banner 的「实际需求」= 月均 × N 月 → 算小 → 超额算大 →
 *       更容易弹出,并给出「建议转货币基金」这种<b>行动建议</b></li>
 * </ul>
 * <p>这是「实时分子 ÷ 均值分母」的错配,而页面上看不出来。</p>
 *
 * <p>成对写:<b>该剔的剔掉</b> + <b>不该剔的别动</b>(窗口不许因此缩水;没有进行中期时行为逐位不变)。</p>
 */
class RecentClosedTest {

    private static final long FAM = 1L;

    private final CashFlowMapper cashFlowMapper = mock(CashFlowMapper.class);
    private final PeriodMemberCashflowMapper pmcMapper = mock(PeriodMemberCashflowMapper.class);
    private final FamilyMapper familyMapper = mock(FamilyMapper.class);
    private final PeriodMapper periodMapper = mock(PeriodMapper.class);

    /** 造 n 期 PMC(id = 1..n,越大越新),每期都填了支出。 */
    private ExpenseLedgerService svc(int n, Long inProgressId) {
        Family f = new Family();
        f.setId(FAM);
        when(familyMapper.findById(anyLong())).thenReturn(Optional.of(f));

        List<FamilyPeriodAggregate> rows = new java.util.ArrayList<>();
        for (long id = n; id >= 1; id--) {   // 倒序 = mapper 的返回顺序
            rows.add(new FamilyPeriodAggregate(id, LocalDate.of(2026, 1, 1).plusMonths(id - 1),
                    new BigDecimal("10000"), new BigDecimal("1000"), 1));
        }
        when(pmcMapper.findFamilyAggregateRecent(anyLong(), anyInt())).thenReturn(rows);

        if (inProgressId != null) {
            Period p = new Period();
            p.setId(inProgressId);
            p.setPeriodStart(LocalDate.of(2026, 1, 1).plusMonths(inProgressId - 1));
            when(periodMapper.findCurrentOpen(anyLong())).thenReturn(Optional.of(p));
        } else {
            when(periodMapper.findCurrentOpen(anyLong())).thenReturn(Optional.empty());
        }
        return new ExpenseLedgerService(cashFlowMapper, pmcMapper, familyMapper, periodMapper);
    }

    private static List<Long> ids(List<ExpenseLedgerService.PeriodExpense> rows) {
        return rows.stream().map(ExpenseLedgerService.PeriodExpense::periodId).toList();
    }

    // ──────────────────────── 该剔的 ────────────────────────

    /** 进行中的那一期(最新一期)必须从均值样本里消失 —— 它只录了半个月。 */
    @Test
    void 剔掉进行中账期() {
        var svc = svc(6, 6L);
        assertThat(ids(svc.recent(FAM, 6))).as("recent 原样包含进行中期").contains(6L);
        assertThat(ids(svc.recentClosed(FAM, 6))).as("recentClosed 必须把它剔掉").doesNotContain(6L);
    }

    /**
     * <b>窗口不许因此缩水</b>。剔掉一期后如果只剩 limit−1 期,月均的分母就无声地变了 ——
     * 那是拿修一个偏差去换另一个偏差。多取一期再过滤。
     */
    @Test
    void 剔掉之后窗口仍是满的_不许无声缩水() {
        var closed = svc(12, 12L).recentClosed(FAM, 6);
        assertThat(closed).as("剔掉进行中期后仍要凑满 6 期").hasSize(6);
        assertThat(ids(closed)).doesNotContain(12L);
        assertThat(ids(closed)).as("应当顺延取到更早一期").contains(6L);
    }

    // ──────────────────────── 不该动的 ────────────────────────

    /** 没有进行中账期(全关账)→ 与 recent 逐位一致,不许有任何漂移。 */
    @Test
    void 没有进行中账期时逐位不变() {
        var svc = svc(6, null);
        assertThat(ids(svc.recentClosed(FAM, 6))).isEqualTo(ids(svc.recent(FAM, 6)));
    }

    /**
     * <b>recent 本身一个字不许改</b> —— 还有三个调用方靠它:
     * 收支趋势图要那个进行中的点(它自己标了浅色 + 「进行中」)、
     * 「已填 N/12 月」问的是填报完整度(本月填了就该算填了)、
     * GoalService 的支出窗口是另一个题目。
     */
    @Test
    void recent不受影响_三个调用方仍拿得到进行中期() {
        var svc = svc(6, 6L);
        assertThat(ids(svc.recent(FAM, 6))).containsExactly(6L, 5L, 4L, 3L, 2L, 1L);
    }

    /** 进行中期不是最新那一期时(数据异常),也只剔它自己,不许误伤别的期。 */
    @Test
    void 只剔进行中那一期_不误伤() {
        var closed = svc(6, 3L).recentClosed(FAM, 6);
        assertThat(ids(closed)).doesNotContain(3L);
        assertThat(ids(closed)).contains(6L, 5L, 4L, 2L, 1L);
    }
}
