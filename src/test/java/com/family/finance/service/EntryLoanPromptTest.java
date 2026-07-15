package com.family.finance.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v0.17.x · 贷款趋势预测提示条显示闸(兼容性核心)。
 * committed==上月值 天然区分「新默认态」(出提示)与「老账期已填预测值」(不出、不回改)。
 */
class EntryLoanPromptTest {

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    @Test
    void newDefault_committedEqualsPrev_showsPrompt() {
        // 新代码开的账期:committed = 上月值 -990000,有建议 -980000,未确认 → 出提示
        assertThat(EntryService.loanPromptVisible(bd("-980000"), bd("-990000"), bd("-990000"), false)).isTrue();
    }

    @Test
    void legacyPeriod_committedEqualsPredicted_hidden() {
        // 老账期:旧代码已把 committed 写成预测值 -980000(≠上月),不打扰、不回改 → 不出提示
        assertThat(EntryService.loanPromptVisible(bd("-980000"), bd("-990000"), bd("-980000"), false)).isFalse();
    }

    @Test
    void userConfirmed_hidden() {
        // 用户已确认(接受/保持上月/手填)→ todo DONE → 不再出提示
        assertThat(EntryService.loanPromptVisible(bd("-980000"), bd("-990000"), bd("-990000"), true)).isFalse();
    }

    @Test
    void noRealSuggestion_predictedEqualsPrev_hidden() {
        // 预测 == 上月(已还平 / 仅一期历史)→ 无建议 → 不出提示
        assertThat(EntryService.loanPromptVisible(bd("0"), bd("0"), bd("0"), false)).isFalse();
    }

    @Test
    void committedManuallyChanged_hidden() {
        // 用户手改成别的值(committed ≠ 上月)→ 非默认态 → 不出提示
        assertThat(EntryService.loanPromptVisible(bd("-980000"), bd("-990000"), bd("-985000"), false)).isFalse();
    }

    @Test
    void nullGuards() {
        assertThat(EntryService.loanPromptVisible(null, bd("-990000"), bd("-990000"), false)).isFalse();
        assertThat(EntryService.loanPromptVisible(bd("-980000"), null, bd("-990000"), false)).isFalse();
        assertThat(EntryService.loanPromptVisible(bd("-980000"), bd("-990000"), null, false)).isFalse();
    }
}
