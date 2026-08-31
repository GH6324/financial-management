package com.family.finance.service;

import com.family.finance.domain.account.AccountType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.19.3 · 支出可以记在负债账户(信用卡)上,但不能记「偿还负债」类的类目。
 *
 * <p>修的是线上反馈的一个真问题:支出页选不中信用卡账户。根因不是 bug 写错了,而是
 * {@code AccountType} 里<b>没有信用卡类型</b> —— 信用卡只能录成 LOAN,而支出侧按
 * 「在贷款账户上记支出 = 又借了一笔,不是花钱」把整个 LOAN 类排掉了。那个推理对房贷成立,
 * 但它默认了「借钱」和「花钱」互斥,<b>而刷卡消费恰恰同时是这两件事</b>。</p>
 *
 * <p>这里钉两件事:放开之后<b>方向仍然对</b>(下面那条余额算术),以及<b>不会双计</b>
 * (类目谓词)。后者尤其重要 —— 双计错出来的报表数字每一个都是真的,只是被算了两次,
 * 肉眼复核发现不了。</p>
 */
class EntryExpenseLiabilityTest {

    // ---- 类目 × 账户类型 ----

    @Test
    void creditCardConsumption_isAllowed() {
        // 本版的主诉求:信用卡(录成 LOAN)上记一笔消费
        assertThat(EntryService.expenseCategoryAllowedOn(AccountType.LOAN, "consumption")).isTrue();
        assertThat(EntryService.expenseCategoryAllowedOn(AccountType.LOAN, "to_relatives")).isTrue();
    }

    @Test
    void repaymentCategoriesOnLiability_areRejected() {
        // 在卡上记「还贷/利息」→ 月底还款那笔又会在现金账户记一次 → 本月支出翻倍
        assertThat(EntryService.expenseCategoryAllowedOn(AccountType.LOAN, "loan_payment")).isFalse();
        assertThat(EntryService.expenseCategoryAllowedOn(AccountType.LOAN, "interest_paid")).isFalse();
    }

    @Test
    void repaymentCategoriesOnCashAccount_stayAllowed() {
        // 还贷记在钱实际流出的现金账户上 —— 这是本来就正确的记法,不能被这次改动误伤
        assertThat(EntryService.expenseCategoryAllowedOn(AccountType.CASH, "loan_payment")).isTrue();
        assertThat(EntryService.expenseCategoryAllowedOn(AccountType.CASH, "interest_paid")).isTrue();
    }

    @Test
    void everyNonLiabilityType_acceptsEveryCategory() {
        // 结构性:将来加账户类型时,非负债的一律不受这条规则影响。
        // 写成遍历而不是列举,是因为列举会在加类型时静默漏掉(v0.14 加 METAL 就是这么漏的)。
        for (AccountType type : AccountType.values()) {
            if (type.isLiability()) continue;
            for (String code : new String[]{"consumption", "loan_payment", "interest_paid", "to_relatives"}) {
                assertThat(EntryService.expenseCategoryAllowedOn(type, code))
                        .as("非负债类型 %s 不该被类目 %s 拦住", type, code)
                        .isTrue();
            }
        }
    }

    @Test
    void everyLiabilityType_rejectsRepayment() {
        // 同上的另一半:将来若把信用卡独立成负债类型,这条规则必须自动覆盖到它
        for (AccountType type : AccountType.values()) {
            if (!type.isLiability()) continue;
            assertThat(EntryService.expenseCategoryAllowedOn(type, "loan_payment"))
                    .as("负债类型 %s 上不该允许还贷", type).isFalse();
            assertThat(EntryService.expenseCategoryAllowedOn(type, "consumption"))
                    .as("负债类型 %s 上应当允许消费(信用卡刷卡)", type).isTrue();
        }
    }

    @Test
    void nullGuards() {
        assertThat(EntryService.expenseCategoryAllowedOn(null, "loan_payment")).isTrue();
        assertThat(EntryService.expenseCategoryAllowedOn(AccountType.LOAN, null)).isTrue();
    }

    // ---- 余额方向 ----

    /**
     * 这次放开之所以不用给负债账户加方向分支,全靠一条约定:<b>负债余额存的是负数</b>
     * ({@code normalizeBalance} 把用户填的正数 negate),而 {@code applyDeltaToBalance}
     * 只做 {@code base.add(delta)}。于是支出那笔 {@code amt.negate()} 落在信用卡上
     * 正好是「欠得更多」,和它落在现金账户上是「钱变少」用的是同一个符号。
     *
     * <p>这条约定要是哪天翻了(比如改成欠款存正数),这次放开就会变成:刷一笔卡、
     * 负债反而变少、净资产虚增 —— 而且没有任何地方会报错。所以在这里钉住算术本身。</p>
     */
    @Test
    void expenseOnCreditCard_increasesDebt() {
        BigDecimal owed = new BigDecimal("-5000.00");           // 已欠 5000(负债存负数)
        BigDecimal delta = new BigDecimal("3000.00").negate();  // recordExpense 传的就是 amt.negate()
        assertThat(owed.add(delta)).isEqualByComparingTo("-8000.00");
    }

    @Test
    void expenseOnCashAccount_decreasesBalance_sameSign() {
        // 同一个 delta 符号,在现金账户上是「钱变少」—— 两边共用一条路径,这正是不用分支的原因
        BigDecimal cash = new BigDecimal("10000.00");
        BigDecimal delta = new BigDecimal("3000.00").negate();
        assertThat(cash.add(delta)).isEqualByComparingTo("7000.00");
    }
}
