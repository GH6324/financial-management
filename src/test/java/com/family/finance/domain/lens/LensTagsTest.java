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
        assertThat(IndustryTag.values()).hasSize(49);   // v1.5 · 20 旧粗值 + 29 申万一级细行业(穿透扩容 · 家电/白酒/电子…)
        assertThat(IndustryTag.labelOf("HOME_APPLIANCE")).isEqualTo("家用电器");   // v1.5 扩容抽查
        assertThat(IndustryTag.labelOf("FOOD_BEVERAGE")).isEqualTo("食品饮料");
        assertThat(IndustryTag.labelOf("MIXED_ALLOC")).isEqualTo("混合配置");
        assertThat(IndustryTag.labelOf("DIVIDEND_UTIL")).isEqualTo("红利公用");
        assertThat(IndustryTag.labelOf("MONEY_CASH")).isEqualTo("货币基金/存款");
        assertThat(IndustryTag.labelOf("FINANCE")).isEqualTo("银行券商保险");
        assertThat(IndustryTag.fromName("FINANCE_ESTATE")).isNull();   // 已拆分,老 code 由 V47 迁移
        assertThat(IndustryTag.fromName("OVERSEAS")).isNull();          // 已删,与地域维重复
        assertThat(AssetClass.labelOf("EQUITY")).isEqualTo("股票股权"); // 平民化命名
        assertThat(IndustryTag.MONEY_CASH.getPinyin()).isEqualTo("huo bi ji jin cun kuan");
    }

}
