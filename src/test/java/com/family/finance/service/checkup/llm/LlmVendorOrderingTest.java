package com.family.finance.service.checkup.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM 主选供应商排序 · v0.14 回归护栏(FR-B1)。
 * 默认 Qwen 主;管理员改「主选=DeepSeek」后,DeepSeek 必须排到最前(故障再切 Qwen)。
 */
class LlmVendorOrderingTest {

    private static LlmClient fake(String vendor) {
        return new LlmClient() {
            @Override public String vendor() { return vendor; }
            @Override public String chat(String s, String u) { return null; }
            @Override public boolean available() { return true; }
        };
    }

    private final LlmClient qwen = fake("qwen");
    private final LlmClient deepseek = fake("deepseek");

    @Test
    void primaryQwen_qwenFirst() {
        List<LlmClient> ordered = LlmDiagnoseService.orderByPrimaryVendor(List.of(qwen, deepseek), "qwen");
        assertThat(ordered).extracting(LlmClient::vendor).containsExactly("qwen", "deepseek");
    }

    @Test
    void primaryDeepseek_deepseekFirst() {
        // 输入顺序仍是 qwen,deepseek(Spring @Order),但主选 deepseek → 排到最前
        List<LlmClient> ordered = LlmDiagnoseService.orderByPrimaryVendor(List.of(qwen, deepseek), "deepseek");
        assertThat(ordered).extracting(LlmClient::vendor).containsExactly("deepseek", "qwen");
    }

    @Test
    void unknownOrNullVendor_keepsOriginalOrder() {
        assertThat(LlmDiagnoseService.orderByPrimaryVendor(List.of(qwen, deepseek), null))
                .extracting(LlmClient::vendor).containsExactly("qwen", "deepseek");
        assertThat(LlmDiagnoseService.orderByPrimaryVendor(List.of(qwen, deepseek), "gemini"))
                .extracting(LlmClient::vendor).containsExactly("qwen", "deepseek");
    }
}
