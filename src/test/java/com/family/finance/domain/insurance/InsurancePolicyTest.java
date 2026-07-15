package com.family.finance.domain.insurance;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountClass;
import com.family.finance.domain.account.AccountLiquidity;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.account.InsuranceSubType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** v0.17 · 保险域纯逻辑单测(不算 IRR/收益 · 仅登记数字 · 液性/归类) */
class InsurancePolicyTest {

    @Test
    void insuranceAccountIsSemiLiquidAsset() {
        Account acc = Account.builder().type(AccountType.INSURANCE).build();
        assertThat(acc.getLiquidity()).isEqualTo(AccountLiquidity.SEMI_LIQUID); // 可退保变现
        assertThat(acc.getAccountClass()).isEqualTo(AccountClass.ASSET);        // 保险计入资产,非负债
    }

    @Test
    void paidPremiumTotalIsPremiumTimesPaidTerms() {
        InsurancePolicy p = InsurancePolicy.builder()
                .premiumAmount(new BigDecimal("12000"))
                .premiumTermsPaid(3)
                .build();
        assertThat(p.paidPremiumTotal()).isEqualByComparingTo("36000"); // 12000 × 3 · 纯登记数字
    }

    @Test
    void paidPremiumTotalNullWhenAnyMissing() {
        assertThat(InsurancePolicy.builder().premiumAmount(new BigDecimal("12000")).build().paidPremiumTotal()).isNull();
        assertThat(InsurancePolicy.builder().premiumTermsPaid(3).build().paidPremiumTotal()).isNull();
    }

    @Test
    void hasAnyFieldReflectsAnyRegisteredValue() {
        assertThat(InsurancePolicy.builder().accountId(1L).build().hasAnyField()).isFalse(); // 只有主键 = 未登记
        assertThat(InsurancePolicy.builder().accountId(1L).insurer("中国人寿").build().hasAnyField()).isTrue();
    }

    @Test
    void subTypeLabelResolvesAndDegradesSafely() {
        assertThat(InsuranceSubType.labelOf("ANNUITY")).isEqualTo("年金险");
        assertThat(InsuranceSubType.labelOf("annuity")).isEqualTo("年金险"); // 大小写不敏感
        assertThat(InsuranceSubType.labelOf("BOGUS")).isEmpty();             // 脏值不抛,返空串
        assertThat(InsuranceSubType.labelOf(null)).isEmpty();
    }

    @Test
    void frequencyLabelMapsToChinese() {
        assertThat(InsurancePolicy.builder().premiumFrequency("ANNUAL").build().frequencyLabel()).isEqualTo("年缴");
        assertThat(InsurancePolicy.builder().premiumFrequency("SINGLE").build().frequencyLabel()).isEqualTo("趸交");
        assertThat(InsurancePolicy.builder().build().frequencyLabel()).isEmpty();
    }
}
