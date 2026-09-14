package com.family.finance.service.expense.imports;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.21 FR-567 · 「记住我的改动」提取的关键字必须<b>可复用</b>。
 *
 * <p>原实现是「取前 20 字」—— 拍脑袋。「瑞幸咖啡(国贸店)」前 20 字仍带门店名,
 * 换一家店就不命中,规则越攒越多却一条都不复用。</p>
 */
class MerchantKeywordTest {

    @Test
    @DisplayName("剥掉门店名 / 编号 / 企业后缀,留下能复用的品牌")
    void stripsVariableParts() {
        assertThat(BillImportService.merchantKeyword("瑞幸咖啡(国贸店)")).isEqualTo("瑞幸咖啡");
        assertThat(BillImportService.merchantKeyword("瑞幸咖啡(国贸店)")).isEqualTo("瑞幸咖啡");
        assertThat(BillImportService.merchantKeyword("星耀餐饮管理有限公司")).isEqualTo("星耀餐饮");
        assertThat(BillImportService.merchantKeyword("永辉超市")).isEqualTo("永辉");
        assertThat(BillImportService.merchantKeyword("美团外卖")).isEqualTo("美团外卖");
    }

    @Test
    @DisplayName("剥过头就退回原名 —— 一个字的关键字会把半个账单都匹配进去")
    void neverReturnsTooShort() {
        assertThat(BillImportService.merchantKeyword("A店")).isEqualTo("A店");
        assertThat(BillImportService.merchantKeyword("(某某)")).isEqualTo("(某某)");
    }

    @Test
    @DisplayName("空值不炸")
    void handlesBlank() {
        assertThat(BillImportService.merchantKeyword(null)).isNull();
        assertThat(BillImportService.merchantKeyword("   ")).isNull();
    }
}
