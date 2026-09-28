package com.family.finance.service.review;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** v1.27(PRD §13 ⑩)· AI 月度复盘缓存键带上账户筛选、币种、偏好;都没有时仍是纯维度 */
class ReviewCacheDimTest {

    @Test
    void plainWhenNothingSet() {
        assertThat(ReviewInsightService.cacheDim("acct", null, List.of())).isEqualTo("acct");
    }

    @Test
    void separatesFilterCurrencyAndPreferences() {
        String a = ReviewInsightService.cacheDim("assetClass", "3,5|CNY", List.of());
        String b = ReviewInsightService.cacheDim("assetClass", "|USD", List.of());
        String c = ReviewInsightService.cacheDim("assetClass", null, List.of("别卖房"));
        assertThat(a).isNotEqualTo(b).isNotEqualTo(c).startsWith("assetClass|");
        assertThat(a.length()).isLessThanOrEqualTo(20);   // review_ai_cache.dim 是 VARCHAR(20)
    }
}
