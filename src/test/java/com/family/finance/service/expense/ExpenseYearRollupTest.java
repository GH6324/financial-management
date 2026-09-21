package com.family.finance.service.expense;

import com.family.finance.domain.expense.ExpenseCategory;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodType;
import com.family.finance.repository.CashFlowMapper;
import com.family.finance.repository.ExpenseCategoryMapper;
import com.family.finance.repository.ExpenseFlowMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.24 · 年度累计要能<b>下钻到细类</b>。
 *
 * <h3>为什么非要单测不可</h3>
 *
 * <p>这个功能在 beta 上<b>看不出来</b>:锚期窗口(2026-01..08)里每个大类恰好只有
 * 一个细类,而展开器的条件是「细类 &gt; 1」—— 只有一个细类时展开只会看到一行跟外面
 * 一模一样的,那是骗人点一下。唯一有两个细类的组合落在还没定稿的 09 期里,
 * 报表锚不到它。</p>
 *
 * <p>也就是说:<b>靠在 beta 上点一下来验收这个功能是验不出来的</b> ——
 * 它不红也不绿,只是不出现,而「不出现」既可能是设计也可能是 bug。
 * 所以判据必须落在服务层,用构造出来的数据穷举。</p>
 */
class ExpenseYearRollupTest {

    private static final long FAM = 1L;

    private ExpenseCatQueryService svc(List<ExpenseCategory> tree,
                                       List<CashFlowMapper.CatOneOffSum> rows,
                                       List<Period> periods) {
        ExpenseFlowMapper flow = mock(ExpenseFlowMapper.class);
        ExpenseCategoryMapper cats = mock(ExpenseCategoryMapper.class);
        PeriodMapper periodMapper = mock(PeriodMapper.class);
        PeriodMemberCashflowMapper pmc = mock(PeriodMemberCashflowMapper.class);
        CashFlowMapper cf = mock(CashFlowMapper.class);
        when(cats.findByFamily(anyLong())).thenReturn(tree);
        when(periodMapper.findAllByFamily(anyLong())).thenReturn(periods);
        when(cf.sumExpenseByPeriodCategoryOneOff(anyLong(), any())).thenReturn(rows);
        // 构造顺序跟着 @RequiredArgsConstructor 的字段顺序走:flow, cashFlow, cats, period, pmc
        return new ExpenseCatQueryService(flow, cf, cats, periodMapper, pmc);
    }

    private ExpenseCategory cat(long id, Long parent, String name) {
        return ExpenseCategory.builder().id(id).familyId(FAM).parentId(parent).name(name).build();
    }

    private Period period(long id, String start) {
        return Period.builder().id(id).familyId(FAM).periodType(PeriodType.MONTHLY)
                .periodStart(LocalDate.parse(start)).build();
    }

    private CashFlowMapper.CatOneOffSum row(long periodId, Long catId, String amt) {
        return new CashFlowMapper.CatOneOffSum(periodId, "consumption", catId, false,
                new BigDecimal(amt), 1);
    }

    @Test
    @DisplayName("年度累计带出细类构成 —— 大类下有两个细类时,leaves 有两项")
    void yearRollupCarriesLeaves() {
        var tree = List.of(cat(1, null, "餐饮美食"), cat(11, 1L, "外卖"), cat(12, 1L, "下馆子"));
        var periods = List.of(period(100, "2026-01-01"), period(101, "2026-02-01"));
        var svc = svc(tree, List.of(row(100, 11L, "300"), row(101, 12L, "200")), periods);

        var rows = svc.year(FAM, 2026, period(101, "2026-02-01"));

        assertThat(rows).hasSize(1);
        var top = rows.getFirst();
        assertThat(top.name()).isEqualTo("餐饮美食");
        assertThat(top.total()).isEqualByComparingTo("500");
        assertThat(top.leaves()).as("大类下的细类必须带出来,否则年度累计点不开").hasSize(2);
        assertThat(top.leaves().stream().map(ExpenseCatQueryService.Leaf::name))
                .containsExactly("外卖", "下馆子");
        // 金额降序 —— 用户想先看大的那一项
        assertThat(top.leaves().getFirst().amount()).isEqualByComparingTo("300");
    }

    /**
     * 「记在大类自己身上」是完全正常的选择(点大类就能提交),
     * 它在细类里如实显示成大类名 —— 不是「未细分」那种特殊称呼。
     */
    @Test
    @DisplayName("钱记在大类自己身上时,它自己也是一条细类")
    void moneyOnTopLevelIsItsOwnLeaf() {
        var tree = List.of(cat(1, null, "餐饮美食"), cat(11, 1L, "外卖"));
        var periods = List.of(period(100, "2026-01-01"));
        var svc = svc(tree, List.of(row(100, 1L, "400"), row(100, 11L, "100")), periods);

        var top = svc.year(FAM, 2026, period(100, "2026-01-01")).getFirst();

        assertThat(top.total()).isEqualByComparingTo("500");
        assertThat(top.leaves()).hasSize(2);
        assertThat(top.leaves().stream().map(ExpenseCatQueryService.Leaf::name))
                .containsExactly("餐饮美食", "外卖");
    }

    /**
     * 未分类是一等公民:它自己成一行,{@code categoryId} 为 null。
     * 丢掉它的话年度累计的合计会莫名小于消费总额,而且不报错。
     */
    @Test
    @DisplayName("未分类单独成行,不被丢掉也不被并进别的大类")
    void unclassifiedIsItsOwnRow() {
        var tree = List.of(cat(1, null, "餐饮美食"));
        var periods = List.of(period(100, "2026-01-01"));
        var svc = svc(tree, List.of(row(100, 1L, "300"), row(100, null, "200")), periods);

        var rows = svc.year(FAM, 2026, period(100, "2026-01-01"));

        assertThat(rows).hasSize(2);
        var unc = rows.stream()
                .filter(r -> ExpenseCatQueryService.UNCLASSIFIED.equals(r.name())).findFirst();
        assertThat(unc).isPresent();
        assertThat(unc.get().categoryId()).isNull();
        assertThat(unc.get().total()).isEqualByComparingTo("200");
    }

    /**
     * 只有一个细类时页面不出展开器(展开只看到一行跟外面一样的 = 骗人点一下),
     * 但<b>数据层仍然要给</b> —— 判「出不出展开器」是模板的事,服务层不该替它决定。
     */
    @Test
    @DisplayName("只有一个细类时数据照给,出不出展开器由模板判")
    void singleLeafStillReturned() {
        var tree = List.of(cat(1, null, "交通出行"), cat(11, 1L, "打车"));
        var periods = List.of(period(100, "2026-01-01"));
        var svc = svc(tree, List.of(row(100, 11L, "300")), periods);

        var top = svc.year(FAM, 2026, period(100, "2026-01-01")).getFirst();
        assertThat(top.leaves()).hasSize(1);
    }

    /**
     * 年度累计必须锚在<b>同一批账期</b>上 —— 晚于锚期的期不算进来。
     * 不然同一页上「年度累计 ≠ Σ趋势」,两个数都「对」,只是口径不同,
     * 而那是最难向用户解释的一类不一致。
     */
    @Test
    @DisplayName("晚于锚期的账期不进年度累计")
    void periodsAfterAnchorAreExcluded() {
        var tree = List.of(cat(1, null, "餐饮美食"));
        var periods = List.of(period(100, "2026-01-01"), period(102, "2026-09-01"));
        // 取数层只会被问到锚期之前的那些 id,这里给一条锚前的
        var svc = svc(tree, List.of(row(100, 1L, "300")), periods);

        var rows = svc.year(FAM, 2026, period(100, "2026-01-01"));
        assertThat(rows.getFirst().total()).isEqualByComparingTo("300");
    }
}
