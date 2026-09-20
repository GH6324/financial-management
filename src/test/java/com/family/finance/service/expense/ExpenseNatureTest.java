package com.family.finance.service.expense;

import com.family.finance.domain.expense.ExpenseCategory;
import com.family.finance.domain.expense.ExpenseNature;
import com.family.finance.repository.ExpenseCategoryMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.24 FR-615/616 · 「一次性」判据的唯一实现,穷举它的输入组合。
 *
 * <p>为什么值得单测:判据本身只有一句话,但它有<b>三段兜底</b>
 * (本级 → 父级 → 弹性),而漏掉最后一段的后果是 NPE ——
 * 而且这个 NPE 发生在报表渲染中途,chunked streaming 下连 /error 页都出不来,
 * 用户看到的是白屏(预检 SF3)。</p>
 */
class ExpenseNatureTest {

    private ExpenseNatureService svc(List<ExpenseCategory> tree) {
        ExpenseCategoryMapper m = mock(ExpenseCategoryMapper.class);
        when(m.findByFamily(anyLong())).thenReturn(tree);
        return new ExpenseNatureService(m);
    }

    private ExpenseCategory cat(long id, Long parentId, String nature) {
        return ExpenseCategory.builder().id(id).familyId(1L).parentId(parentId)
                .name("c" + id).expenseNature(nature).build();
    }

    @Test
    @DisplayName("本级设过性质 → 用本级的")
    void ownNatureWins() {
        var s = svc(List.of(cat(1, null, "RIGID"), cat(2, 1L, "FLEX")));
        assertThat(s.natureMap(1L).get(2L)).isEqualTo(ExpenseNature.FLEX);
    }

    @Test
    @DisplayName("本级没设 → 继承父级")
    void inheritsParent() {
        var s = svc(List.of(cat(1, null, "RIGID"), cat(2, 1L, null)));
        assertThat(s.natureMap(1L).get(2L)).isEqualTo(ExpenseNature.RIGID);
    }

    /**
     * 预检 SF3 的那一格。父级也是 NULL 时,「取父级值」再 valueOf 会 NPE ——
     * 而它在报表渲染中途抛,页面白屏。兜底必须在这里。
     */
    @Test
    @DisplayName("本级与父级都没设 → 弹性,不是 null、不抛异常(SF3)")
    void bothNullFallsBackToFlex() {
        var s = svc(List.of(cat(1, null, null), cat(2, 1L, null)));
        assertThat(s.natureMap(1L).get(2L)).isEqualTo(ExpenseNature.FLEX);
        assertThat(s.natureMap(1L).get(1L)).isEqualTo(ExpenseNature.FLEX);
    }

    @Test
    @DisplayName("库里是脏值 → 按弹性读,不抛(FR-616)")
    void garbageNatureIsFlex() {
        var s = svc(List.of(cat(1, null, "SOMETHING_ELSE")));
        assertThat(s.natureMap(1L).get(1L)).isEqualTo(ExpenseNature.FLEX);
        assertThat(ExpenseNature.parse(null)).isEqualTo(ExpenseNature.FLEX);
        assertThat(ExpenseNature.parse("  ")).isEqualTo(ExpenseNature.FLEX);
    }

    @Test
    @DisplayName("FR-615 · 逐笔勾优先于类目性质 —— 刚性类目里勾了一次性,就是一次性")
    void rowFlagBeatsCategory() {
        var s = svc(List.of(cat(1, null, "RIGID")));
        Map<Long, ExpenseNature> m = s.natureMap(1L);
        assertThat(s.natureOf(true, 1L, m)).isEqualTo(ExpenseNature.ONE_OFF);
        assertThat(s.natureOf(false, 1L, m)).isEqualTo(ExpenseNature.RIGID);
    }

    /**
     * FR-636 · 未分类算弹性,<b>不是丢掉</b>。
     * 丢掉的话三分合计会小于支出总额,而那是这个项目明令禁止的一类错。
     */
    @Test
    @DisplayName("FR-636 · 未分类(categoryId=null)算弹性,不许丢掉")
    void unclassifiedIsFlexNotDropped() {
        var s = svc(List.of(cat(1, null, "RIGID")));
        assertThat(s.natureOf(false, null, s.natureMap(1L))).isEqualTo(ExpenseNature.FLEX);
    }

    @Test
    @DisplayName("类目被删了但流水还挂着旧 id → 弹性,不抛")
    void danglingCategoryIsFlex() {
        var s = svc(List.of(cat(1, null, "RIGID")));
        assertThat(s.natureOf(false, 999L, s.natureMap(1L))).isEqualTo(ExpenseNature.FLEX);
    }
}
