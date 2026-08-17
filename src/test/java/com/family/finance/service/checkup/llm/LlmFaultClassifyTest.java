package com.family.finance.service.checkup.llm;

import com.family.finance.service.config.FamilyConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * LLM 故障归类护栏 · 承接 v0.6 的 {@code QwenInsightComplianceTest} 前半段,v1.13 扩到两家。
 *
 * <p>归类决定<b>失败之后往哪走</b>,错一格就是两种坏体验:
 * <ul>
 *   <li>「单型号免费额度用尽」误判成账户级 → 明明换个型号就能答,却直接切了备选平台(甚至花钱)</li>
 *   <li>「欠费 / 接入点不存在」误判成瞬时 → 在必错的一家上把重试和超时耗满,用户干等</li>
 * </ul>
 * 所以每加一家平台,这里都要有它自己的错误码样本。</p>
 */
class LlmFaultClassifyTest {

    private static DashScopeLlmClient dashscope() {
        return new DashScopeLlmClient(mock(FamilyConfigService.class), new RestTemplateBuilder());
    }

    private static ArkLlmClient ark() {
        return new ArkLlmClient(mock(FamilyConfigService.class), new RestTemplateBuilder());
    }

    // ---------------- 百炼:额度码要和账户码分开 ----------------

    @Test
    void dashscope_freeQuotaExhausted_isModelLevel() {
        DashScopeLlmClient c = dashscope();
        // 429 Throttling.AllocationQuota / insufficient_quota / Free allocated quota exceeded
        assertThat(c.classify(429,
                "{\"error\":{\"code\":\"Throttling.AllocationQuota\",\"message\":\"Free allocated quota exceeded.\"}}"))
                .isEqualTo(AbstractOpenAiCompatibleClient.Fault.MODEL_QUOTA);
        assertThat(c.classify(429, "{\"error\":{\"code\":\"insufficient_quota\"}}"))
                .isEqualTo(AbstractOpenAiCompatibleClient.Fault.MODEL_QUOTA);
        // 403 AllocationQuota.FreeTierOnly —— 默认归类会把 403 当账户级,这里必须被百炼的覆写截住
        assertThat(c.classify(403,
                "{\"error\":{\"code\":\"AllocationQuota.FreeTierOnly\",\"message\":\"The free tier of the model has been exhausted\"}}"))
                .isEqualTo(AbstractOpenAiCompatibleClient.Fault.MODEL_QUOTA);
    }

    @Test
    void dashscope_accountArrearage_isFatal() {
        DashScopeLlmClient c = dashscope();
        // 400 Arrearage 欠费 / 账单过期 → 账户级 · 立刻切备选(切型号没有意义)
        assertThat(c.classify(400,
                "{\"error\":{\"code\":\"Arrearage\",\"message\":\"Access denied, please make sure your account is in good standing.\"}}"))
                .isEqualTo(AbstractOpenAiCompatibleClient.Fault.ACCOUNT_FATAL);
        assertThat(c.classify(429,
                "{\"error\":{\"code\":\"PrepaidBillOverdue\",\"message\":\"The prepaid bill is overdue.\"}}"))
                .isEqualTo(AbstractOpenAiCompatibleClient.Fault.ACCOUNT_FATAL);
    }

    @Test
    void dashscope_otherErrors_areTransient() {
        DashScopeLlmClient c = dashscope();
        assertThat(c.classify(500, "internal error"))
                .isEqualTo(AbstractOpenAiCompatibleClient.Fault.TRANSIENT);
        assertThat(c.classify(429,
                "{\"error\":{\"code\":\"Throttling.RateQuota\",\"message\":\"Requests rate limit exceeded\"}}"))
                .isEqualTo(AbstractOpenAiCompatibleClient.Fault.TRANSIENT);
    }

    @Test
    void dashscope_badKey_isFatal_notTransient() {
        // v1.13 口径修正:401 是 key 写错/失效,重试到天亮也一样 → 账户级,交给路由切备选
        assertThat(dashscope().classify(401, ""))
                .isEqualTo(AbstractOpenAiCompatibleClient.Fault.ACCOUNT_FATAL);
    }

    // ---------------- 方舟:配置错要当场判死,别耗超时 ----------------

    @Test
    void ark_endpointOrModelProblems_areFatal() {
        ArkLlmClient c = ark();
        assertThat(c.classify(404,
                "{\"error\":{\"code\":\"ModelNotOpen\",\"message\":\"The model is not activated\"}}"))
                .isEqualTo(AbstractOpenAiCompatibleClient.Fault.ACCOUNT_FATAL);
        assertThat(c.classify(400,
                "{\"error\":{\"code\":\"InvalidEndpointOrModel\",\"message\":\"invalid endpoint\"}}"))
                .isEqualTo(AbstractOpenAiCompatibleClient.Fault.ACCOUNT_FATAL);
        assertThat(c.classify(404, "{\"error\":{\"message\":\"model not found\"}}"))
                .isEqualTo(AbstractOpenAiCompatibleClient.Fault.ACCOUNT_FATAL);
        assertThat(c.classify(403, "{\"error\":{\"code\":\"AccessDenied\"}}"))
                .isEqualTo(AbstractOpenAiCompatibleClient.Fault.ACCOUNT_FATAL);
    }

    @Test
    void ark_hasNoModelRotation_soQuotaCodesAreNotModelLevel() {
        // 方舟不摊免费额度(PRD 拍板 3):不能把百炼那套额度码语义带过来,
        // 否则会在同一个型号上原地打转。
        assertThat(ark().classify(429, "{\"error\":{\"code\":\"AllocationQuota\"}}"))
                .isEqualTo(AbstractOpenAiCompatibleClient.Fault.TRANSIENT);
        assertThat(ark().classify(500, "internal"))
                .isEqualTo(AbstractOpenAiCompatibleClient.Fault.TRANSIENT);
    }
}
