package com.family.finance.service.checkup.llm;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v0.6 · Qwen 多模型额度故障分类 + 资产洞察合规校验守护。
 */
class QwenInsightComplianceTest {

    // ---------------- Qwen 故障分类 ----------------
    @Test
    void classify_free_quota_exhausted_switches_model() {
        // 429 Throttling.AllocationQuota / insufficient_quota / Free allocated quota exceeded
        assertThat(QwenLlmClient.classify(429,
                "{\"error\":{\"code\":\"Throttling.AllocationQuota\",\"message\":\"Free allocated quota exceeded.\"}}"))
                .isEqualTo(QwenLlmClient.Fault.QUOTA_EXHAUSTED);
        assertThat(QwenLlmClient.classify(429,
                "{\"error\":{\"code\":\"insufficient_quota\"}}"))
                .isEqualTo(QwenLlmClient.Fault.QUOTA_EXHAUSTED);
        // 403 AllocationQuota.FreeTierOnly
        assertThat(QwenLlmClient.classify(403,
                "{\"error\":{\"code\":\"AllocationQuota.FreeTierOnly\",\"message\":\"The free tier of the model has been exhausted\"}}"))
                .isEqualTo(QwenLlmClient.Fault.QUOTA_EXHAUSTED);
    }

    @Test
    void classify_account_arrearage_is_fatal() {
        // 400 Arrearage 欠费 / 账单过期 → 账户级 · 立刻 failover(不切模型)
        assertThat(QwenLlmClient.classify(400,
                "{\"error\":{\"code\":\"Arrearage\",\"message\":\"Access denied, please make sure your account is in good standing.\"}}"))
                .isEqualTo(QwenLlmClient.Fault.ARREARAGE);
        assertThat(QwenLlmClient.classify(429,
                "{\"error\":{\"code\":\"PrepaidBillOverdue\",\"message\":\"The prepaid bill is overdue.\"}}"))
                .isEqualTo(QwenLlmClient.Fault.ARREARAGE);
    }

    @Test
    void classify_other_errors_are_transient() {
        assertThat(QwenLlmClient.classify(500, "internal error")).isEqualTo(QwenLlmClient.Fault.TRANSIENT);
        assertThat(QwenLlmClient.classify(429,
                "{\"error\":{\"code\":\"Throttling.RateQuota\",\"message\":\"Requests rate limit exceeded\"}}"))
                .isEqualTo(QwenLlmClient.Fault.TRANSIENT);
        assertThat(QwenLlmClient.classify(401, "")).isEqualTo(QwenLlmClient.Fault.TRANSIENT);
    }

    // ---------------- 资产洞察合规校验 ----------------
    private static final String CLEAN = "本次资产洞察显示:房产在总资产中的占比偏高,集中度处于参考风险线附近,"
            + "需关注单一不动产敞口对家庭流动性的挤压。负债率处于健康区间,加权负债利率与资产名义年化收益"
            + "之间的关系可作为是否加速偿还的纪律性参考,而非择机判断。现金桶当前占比较高,在低利率与资产荒的"
            + "环境下,扣除通胀后的真实购买力可能承压,相对社会平均财富的位置也值得留意。综合来看,家庭资产"
            + "结构整体稳健,建议保持再平衡纪律,按既定配置锚定期检视各桶偏离方向:超配的桶逐步减配、低配的桶"
            + "稳步补足,优先处理偏离最大的一项,流动性与风险敞口维持当前水平即可,无需大幅调整。";

    @Test
    void checkInsight_accepts_clean_neutral_text() {
        assertThat(OutputValidator.checkInsight(CLEAN, Set.of()).accepted()).isTrue();
    }

    @Test
    void checkInsight_rejects_prediction_and_timing() {
        // 预测涨跌
        assertThat(OutputValidator.checkInsight(
                CLEAN + "预计股市即将上涨,建议看涨。", Set.of()).accepted()).isFalse();
        // 择时/买卖时点
        assertThat(OutputValidator.checkInsight(
                CLEAN + "建议现在买入并波段操作高抛低吸。", Set.of()).accepted()).isFalse();
        // 牛市预测
        assertThat(OutputValidator.checkInsight(
                CLEAN + "后续大概率进入牛市。", Set.of()).accepted()).isFalse();
    }

    @Test
    void checkInsight_still_enforces_guarantee_and_product_bans() {
        // 担保话术(继承 check 的合规底线)
        assertThat(OutputValidator.checkInsight(
                CLEAN + "本组合保证零风险稳赚。", Set.of()).accepted()).isFalse();
        // 具体产品名
        assertThat(OutputValidator.checkInsight(
                CLEAN + "建议把现金转入余额宝。", Set.of()).accepted()).isFalse();
    }
}
