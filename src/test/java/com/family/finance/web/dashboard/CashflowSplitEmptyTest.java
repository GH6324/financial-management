package com.family.finance.web.dashboard;

import com.family.finance.factview.CashflowBreakdown;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v0.12.2 · dashboard「本期怎么变的」空态判定:收入侧切 cash_flow(FR-142)后,
 * 仅录了收入录入(股票 +股数,PMC 2框未填)也应视为「已填」,不再提示去填报。
 */
class CashflowSplitEmptyTest {

    private static CashflowBreakdown bk(String inc, String exp) {
        BigDecimal i = new BigDecimal(inc), e = new BigDecimal(exp);
        return new CashflowBreakdown(i, e, i.subtract(e));
    }

    @Test
    void empty_whenNoPmcAndNoCashflow() {
        var v = CashflowSplitView.of(new BigDecimal("1000"), bk("0", "0"), 0, 3);
        assertThat(v.empty()).isTrue();
    }

    @Test
    void notEmpty_whenCashflowIncomeButPmcUnfilled() {
        // 只录了股票 +股数收入(cash_flow income>0),PMC 未填(filledMembers=0)→ 不判空
        var v = CashflowSplitView.of(new BigDecimal("30000"), bk("28542.30", "0"), 0, 3);
        assertThat(v.empty()).isFalse();
    }

    @Test
    void notEmpty_whenPmcFilled() {
        var v = CashflowSplitView.of(new BigDecimal("1000"), bk("0", "0"), 2, 3);
        assertThat(v.empty()).isFalse();
    }

    @Test
    void notEmpty_whenOnlyExpense() {
        var v = CashflowSplitView.of(new BigDecimal("-500"), bk("0", "500"), 0, 3);
        assertThat(v.empty()).isFalse();
    }
}
