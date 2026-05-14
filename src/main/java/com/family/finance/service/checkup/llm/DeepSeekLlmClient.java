package com.family.finance.service.checkup.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * DeepSeek-Chat (v3) 客户端 · v0.2 FR-40c · 决策 6 备用
 *
 * 端点:https://api.deepseek.com/chat/completions
 * Bearer:env DEEPSEEK_API_KEY
 */
@Component
@Order(2)  // DeepSeek 备用,优先级 2(Qwen 失败时 fallback)
@Slf4j
public class DeepSeekLlmClient implements LlmClient {

    private static final String API = "https://api.deepseek.com/chat/completions";
    private static final String MODEL = "deepseek-chat";
    private static final int FAIL_THRESHOLD = 3;
    private static final long COOLDOWN_MS = 60_000L;

    private final String apiKey;
    private final RestTemplate restTemplate;

    private volatile int consecutiveFailures = 0;
    private volatile Instant breakerOpenedAt = Instant.EPOCH;

    public DeepSeekLlmClient(@Value("${finance.llm.deepseek.api-key:}") String apiKey,
                             RestTemplateBuilder builder) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(5))
                // 综合诊断长 prompt,DeepSeek 较慢,加宽到 25s
                .setReadTimeout(Duration.ofSeconds(25))
                .build();
    }

    @Override
    public String vendor() { return "deepseek"; }

    @Override
    public boolean available() {
        if (apiKey.isBlank()) return false;
        if (consecutiveFailures >= FAIL_THRESHOLD) {
            long since = java.time.Duration.between(breakerOpenedAt, Instant.now()).toMillis();
            return since >= COOLDOWN_MS;
        }
        return true;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        if (apiKey.isBlank()) throw new IllegalStateException("DeepSeek API key 未配置");

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(apiKey);
        Map<String, Object> body = Map.of(
                "model", MODEL,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                // 综合诊断需要 LLM 有更多发挥(2026-05-10 从 0.15 调到 0.5)
                "temperature", 0.5,
                // v0.4.9+:JSON 结构化输出 750 太严易截断 · 提到 2000
                "max_tokens", 2000
        );

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.postForObject(API, new HttpEntity<>(body, h), Map.class);
            if (resp == null) throw new RuntimeException("DeepSeek 返回 null");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
            if (choices == null || choices.isEmpty()) throw new RuntimeException("DeepSeek 无 choices");
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
            if (msg == null) throw new RuntimeException("DeepSeek 无 message");
            String content = (String) msg.get("content");
            if (content == null || content.isBlank()) throw new RuntimeException("DeepSeek 空 content");

            // 检测 token 限制截断
            String finishReason = (String) choices.get(0).get("finish_reason");
            if ("length".equals(finishReason)) {
                log.warn("DeepSeek 输出因 max_tokens 截断 · content.len={} · 建议提高 max_tokens", content.length());
            }

            consecutiveFailures = 0;
            return content;
        } catch (Exception e) {
            consecutiveFailures++;
            if (consecutiveFailures >= FAIL_THRESHOLD) {
                breakerOpenedAt = Instant.now();
                log.warn("DeepSeek circuit breaker 触发,冷却 {} 秒", COOLDOWN_MS / 1000);
            }
            throw new RuntimeException("DeepSeek 调用失败: " + e.getMessage(), e);
        }
    }
}
