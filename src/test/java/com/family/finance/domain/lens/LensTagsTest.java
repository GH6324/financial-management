package com.family.finance.domain.lens;

import com.family.finance.domain.account.AccountType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** v1.1 · 打标域 + AI 白名单校验单测 */
class LensTagsTest {

    @Test
    void assetClassDefaultForCoversAllTypes() {
        assertThat(AssetClass.defaultFor(AccountType.CASH, null)).isEqualTo(AssetClass.CASH_EQ);
        assertThat(AssetClass.defaultFor(AccountType.STOCK, null)).isEqualTo(AssetClass.EQUITY);
        assertThat(AssetClass.defaultFor(AccountType.CRYPTO, null)).isEqualTo(AssetClass.ALTERNATIVE);
        assertThat(AssetClass.defaultFor(AccountType.METAL, null)).isEqualTo(AssetClass.ALTERNATIVE);
        assertThat(AssetClass.defaultFor(AccountType.PROPERTY, null)).isEqualTo(AssetClass.REAL_ESTATE);
        assertThat(AssetClass.defaultFor(AccountType.INSURANCE, null)).isEqualTo(AssetClass.INSURANCE);
        assertThat(AssetClass.defaultFor(AccountType.WEALTH, "MONEY_FUND")).isEqualTo(AssetClass.CASH_EQ); // 货基=现金及等价
        assertThat(AssetClass.defaultFor(AccountType.WEALTH, "BANK_WEALTH")).isEqualTo(AssetClass.FIXED_INCOME);
        assertThat(AssetClass.defaultFor(AccountType.LOAN, null)).isNull();   // 负债不进大类
        assertThat(AssetClass.defaultFor(AccountType.OTHER, null)).isNull();  // 不装懂 → 未分类
    }

    @Test
    void enumsParseSafelyAndLabel() {
        assertThat(IndustryTag.labelOf("NEW_ENERGY")).isEqualTo("新能源电力");
        assertThat(IndustryTag.labelOf("new_energy")).isEqualTo("新能源电力");
        assertThat(IndustryTag.labelOf("BOGUS")).isEmpty();
        assertThat(AssetClass.labelOf(null)).isEmpty();
        assertThat(IndustryTag.values()).hasSize(12);   // D3 已拍板 · 12 粗行业
    }

}
