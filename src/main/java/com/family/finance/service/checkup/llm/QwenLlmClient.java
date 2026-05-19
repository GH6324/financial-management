package com.family.finance.service.checkup.llm;

import com.family.finance.service.config.FamilyConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 阿里 Qwen-Plus 客户端 (OpenAI 兼容) · v0.2 FR-40c · 决策 6 + 决策 20
 *
 * 端点:https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
 * 模型:qwen-plus(2026-05-10 从 qwen-turbo 升级,综合诊断需要更强模型)
 * Bearer:env FINANCE_LLM_QWEN_API_KEY
 *
 * 简单 circuit breaker:连续 3 次失败 → 60 秒拒绝调用。
 */
@Component
@Order(1)  // Qwen 主用,优先级 1(数字小 → 优先)
@Slf4j
public class QwenLlmClient implements LlmClient {

    private static final String API = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String MODEL = "qwen-plus";
    private static final int FAIL_THRESHOLD = 3;
    private static final long COOLDOWN_MS = 60_000L;
    /** 单家庭模式 · 见 prd §22.3 类 A · 与 backup.sh FAMILY_ID=1 一致 */
    private static final long FAMILY_ID = 1L;

    private final FamilyConfigService configService;
    private final RestTemplate restTemplate;

    private volatile int consecutiveFailures = 0;
    private volatile Instant breakerOpenedAt = Instant.EPOCH;

    public QwenLlmClient(FamilyConfigService configService, RestTemplateBuilder builder) {
        this.configService = configService;
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(5))
                // 综合诊断 token 长 · timeout 仍 static(改要重启)· apiKey/max_tokens 已 dynamic
                .setReadTimeout(Duration.ofSeconds(25))
                .build();
    }

    /** v0.4.18 · 每次调用从 ConfigService 读 API key(DB > env fallback)· 私密 */
    private String currentApiKey() {
        return configService.getString(FAMILY_ID, FamilyConfigService.K_LLM_QWEN_KEY, "");
    }

    /** v0.4.18 · max_tokens 动态可调 · 默认 2000 */
    private int currentMaxTokens() {
        return configService.getInt(FAMILY_ID, FamilyConfigService.K_LLM_MAX_TOKENS, 2000);
    }

    @Override
    public String vendor() { return "qwen"; }

    @Override
    public boolean available() {
        if (currentApiKey().isBlank()) return false;
        if (consecutiveFailures >= FAIL_THRESHOLD) {
            long since = java.time.Duration.between(breakerOpenedAt, Instant.now()).toMillis();
            return since >= COOLDOWN_MS;  // 冷却结束才尝试半开
        }
        return true;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        String key = currentApiKey();
        if (key.isBlank()) throw new IllegalStateException("Qwen API key 未配置");

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(key);
        Map<String, Object> body = Map.of(
                "model", MODEL,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                // 综合诊断需要 LLM 有更多发挥(2026-05-10 从 0.15 调到 0.5)
                "temperature", 0.5,
                // v0.4.18 · max_tokens 改读 ConfigService 动态可调 · 默认 2000(v0.4.10 起的平衡值)
                "max_tokens", currentMaxTokens()
        );

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.postForObject(API, new HttpEntity<>(body, h), Map.class);
            if (resp == null) throw new RuntimeException("Qwen 返回 null");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
            if (choices == null || choices.isEmpty()) throw new RuntimeException("Qwen 无 choices");
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
            if (msg == null) throw new RuntimeException("Qwen 无 message");
            String content = (String) msg.get("content");
            if (content == null || content.isBlank()) throw new RuntimeException("Qwen 空 content");

            // 检测 token 限制截断 · finish_reason=length 表示输出未完整
            String finishReason = (String) choices.get(0).get("finish_reason");
            if ("length".equals(finishReason)) {
                log.warn("Qwen 输出因 max_tokens 截断 · content.len={} · 建议提高 max_tokens", content.length());
            }

            consecutiveFailures = 0;
            return content;
        } catch (Exception e) {
            consecutiveFailures++;
            if (consecutiveFailures >= FAIL_THRESHOLD) {
                breakerOpenedAt = Instant.now();
                log.warn("Qwen circuit breaker 触发,冷却 {} 秒", COOLDOWN_MS / 1000);
            }
            throw new RuntimeException("Qwen 调用失败: " + e.getMessage(), e);
        }
    }
}
