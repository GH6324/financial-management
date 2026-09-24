package com.family.finance.domain.transfer;

import com.family.finance.calc.ReconciliationCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #21 · 跨币种划转:转入方「收到了多少」必须按它自己的币种算。
 *
 * <p>用户的原场景:人民币账户 A 转 1000 到美元账户 B,B 实到 100 美元(汇率取 1:10 便于算)。
 * 两边余额都更新对了,B 却提示「900 未解释」—— 因为给 B 算已知流入时用了 amount(1000 人民币)。</p>
 */
class TransferReceivedAmountTest {

    private static BigDecimal d(String s) { return new BigDecimal(s); }

    @Test
    @DisplayName("同币种:toAmount 为空 → 收到的就是 amount")
    void sameCurrency() {
        Transfer t = Transfer.builder().amount(d("500")).toAmount(null).build();
        assertThat(t.receivedAmount()).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("跨币种:收到的是 toAmount(转入账户币种),不是转出方付出的 amount")
    void crossCurrency() {
        Transfer t = Transfer.builder().amount(d("1000")).toAmount(d("100")).build();
        assertThat(t.receivedAmount()).isEqualByComparingTo("100");
    }

    /**
     * 把 issue 里的数原样代进去:B 期初 100 美元,收到 100 美元后期末 200 美元。
     * 用 receivedAmount 算,差额是 0;用 amount 算(原来的写法),差额正好是 issue 里的 −900。
     */
    @Test
    @DisplayName("issue #21 原场景:美元账户不再冒出 900 的未解释差额")
    void issue21Scenario() {
        Transfer t = Transfer.builder().amount(d("1000")).toAmount(d("100")).build();
        BigDecimal prev = d("100"), end = d("200");

        BigDecimal fixed = ReconciliationCalculator.unexplained(end, prev,
                BigDecimal.ZERO, BigDecimal.ZERO, t.receivedAmount(), BigDecimal.ZERO);
        BigDecimal wasBuggy = ReconciliationCalculator.unexplained(end, prev,
                BigDecimal.ZERO, BigDecimal.ZERO, t.getAmount(), BigDecimal.ZERO);

        assertThat(fixed).isEqualByComparingTo("0");
        assertThat(wasBuggy).as("原来的写法 —— 用来证明这条测试确实复现了 issue")
                .isEqualByComparingTo("-900");
    }
}
