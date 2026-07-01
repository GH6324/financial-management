package com.family.finance.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v0.11.2 · LOAN 开账预填夹零(bug2 回归):贷款余额是欠款,应 ≤ 0。
 * 修「还平到 0 后按趋势外推成正数」—— 迪娃-税务欠款 05:-72000 → 06:0,07 曾被外推成 +72000。
 */
class PeriodOpenerLoanPrefillTest {

    @Test
    void paidOffLoan_doesNotExtrapolatePositive() {
        // 05 = -72000,06 = 0(转账还平);07 预测应为 0,而不是 0 + 72000 = +72000
        BigDecimal predicted = PeriodOpener.predictLoanBalance(new BigDecimal("0"), new BigDecimal("-72000"));
        assertThat(predicted).isEqualByComparingTo("0");
    }

    @Test
    void mortgage_steadyPaydown_stillExtrapolates() {
        // 房贷匀速还款仍为负 → 正常外推,不被夹:-1000000 → -990000 → 预测 -980000
        BigDecimal predicted = PeriodOpener.predictLoanBalance(new BigDecimal("-990000"), new BigDecimal("-1000000"));
        assertThat(predicted).isEqualByComparingTo("-980000");
    }

    @Test
    void growingDebt_extrapolatesMoreNegative() {
        // 欠款增加:-50000 → -100000 → 预测 -150000(仍 ≤ 0,不夹)
        BigDecimal predicted = PeriodOpener.predictLoanBalance(new BigDecimal("-100000"), new BigDecimal("-50000"));
        assertThat(predicted).isEqualByComparingTo("-150000");
    }

    @Test
    void onlyOneHistory_carriesPrev() {
        // 仅一期历史(prevPrev=null)→ 沿用 prev,不外推
        assertThat(PeriodOpener.predictLoanBalance(new BigDecimal("-30000"), null)).isEqualByComparingTo("-30000");
    }

    @Test
    void alreadyZero_staysZero() {
        // 已还平且上上期也是 0 → 维持 0
        assertThat(PeriodOpener.predictLoanBalance(new BigDecimal("0"), new BigDecimal("0"))).isEqualByComparingTo("0");
    }

    @Test
    void wouldCrossZero_clampedToZero() {
        // 大额提前还款把欠款打过头:-10000 → 上期还了 40000(prevPrev=-50000)→ 外推 +30000 → 夹到 0
        BigDecimal predicted = PeriodOpener.predictLoanBalance(new BigDecimal("-10000"), new BigDecimal("-50000"));
        assertThat(predicted).isEqualByComparingTo("0");
    }
}
