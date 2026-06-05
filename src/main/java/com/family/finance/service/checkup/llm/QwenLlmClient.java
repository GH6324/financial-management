package com.family.finance.service.checkup.llm;

import com.family.finance.service.config.FamilyConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 阿里 Qwen 客户端 (OpenAI 兼容) · v0.2 FR-40c · v0.6 多模型额度兜底(FR-108)
 *
 * <p>端点:https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
 * Bearer:{@link FamilyConfigService#K_LLM_QWEN_KEY}(DB > env · 私密)</p>
 *
 * <p><b>v0.6 多模型兜底</b>:百炼免费额度按「单模型」独立计量,用尽某模型返回
 * 429 {@code Throttling.AllocationQuota / insufficient_quota / Free allocated quota exceeded}
 * 或 403 {@code AllocationQuota.FreeTierOnly}。此时<b>切到列表里下一个模型</b>(各自独立额度),
 * 把用尽的模型标记冷却(默认 6h · 免费额度通常按周期重置)。
 * 账户级故障(400 {@code Arrearage} 欠费 / {@code *BillOverdue} 账单过期)对所有模型一致 ——
 * <b>立刻抛出</b>,由上层路由 failover 到 DeepSeek,不浪费在切模型上。</p>
 *
 * <p>模型列表 {@link FamilyConfigService#K_LLM_QWEN_MODELS} 运营可配(≤10 · 逗号分隔有序)。</p>
 */
@Component
@Order(1)  // Qwen 主用,优先级 1(数字小 → 优先)
@Slf4j
public class QwenLlmClient implements LlmClient {

    private static final String API = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    /** 默认有序模型列表(各自独立免费额度)· 运营可在管理页覆盖 */
    private static final String DEFAULT_MODELS =
            "qwen-plus,qwen-flash,qwen-turbo,qwen2.5-72b-instruct,qwen2.5-32b-instruct,"
            + "qwen2.5-14b-instruct,qwen2.5-7b-instruct,qwen-long,qwen-plus-latest,qwen-turbo-latest";
    private static final int MAX_MODELS = 10;
    private static final int FAIL_THRESHOLD = 3;
    private static final long COOLDOWN_MS = 60_000L;
    /** 某模型免费额度用尽后的冷却(免费额度按周期重置 · 6h 内不再尝试该模型) */
    private static final long MODEL_EXHAUST_COOLDOWN_MS = 6L * 60 * 60 * 1000;
    /** 单家庭模式 · 见 prd §22.3 类 A · 与 backup.sh FAMILY_ID=1 一致 */
    private static final long FAMILY_ID = 1L;

    private final FamilyConfigService configService;
    private final RestTemplate restTemplate;

    /** 连续(非额度类)失败 → 短熔断,防雪崩 */
    private volatile int consecutiveFailures = 0;
    private volatile Instant breakerOpenedAt = Instant.EPOCH;
    /** 模型 → 额度用尽冷却到期时刻 */
    private final ConcurrentHashMap<String, Instant> modelExhaustedUntil = new ConcurrentHashMap<>();

    public QwenLlmClient(FamilyConfigService configService, RestTemplateBuilder builder) {
        this.configService = configService;
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(25))
                .build();
    }

    private String currentApiKey() {
        return configService.getString(FAMILY_ID, FamilyConfigService.K_LLM_QWEN_KEY, "");
    }

    private int currentMaxTokens() {
        return configService.getInt(FAMILY_ID, FamilyConfigService.K_LLM_MAX_TOKENS, 2000);
    }

    /** 解析有序模型列表(去重 · 去空 · 上限 10)· 配置空则用默认 */
    List<String> currentModels() {
        String raw = configService.getString(FAMILY_ID, FamilyConfigService.K_LLM_QWEN_MODELS, "");
        if (raw == null || raw.isBlank()) raw = DEFAULT_MODELS;
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String m = part.trim();
            if (!m.isEmpty()) ordered.add(m);
            if (ordered.size() >= MAX_MODELS) break;
        }
        if (ordered.isEmpty()) ordered.add("qwen-plus");
        return new ArrayList<>(ordered);
    }

    private boolean modelAvailable(String model) {
        Instant until = modelExhaustedUntil.get(model);
        return until == null || Instant.now().isAfter(until);
    }

    @Override
    public String vendor() { return "qwen"; }

    @Override
    public boolean available() {
        if (currentApiKey().isBlank()) return false;
        // 短熔断未冷却结束 → 不可用
        if (consecutiveFailures >= FAIL_THRESHOLD) {
            long since = Duration.between(breakerOpenedAt, Instant.now()).toMillis();
            if (since < COOLDOWN_MS) return false;
        }
        // 至少有一个模型未处于额度冷却
        return currentModels().stream().anyMatch(this::modelAvailable);
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        String key = currentApiKey();
        if (key.isBlank()) throw new IllegalStateException("Qwen API key 未配置");

        List<String> models = currentModels();
        RuntimeException lastTransient = null;
        boolean anyTried = false;

        for (String model : models) {
            if (!modelAvailable(model)) continue;
            anyTried = true;
            try {
                String content = callOnce(key, model, systemPrompt, userPrompt);
                consecutiveFailures = 0;       // 成功 → 复位短熔断
                return content;
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                String body = e.getResponseBodyAsString();
                Fault fault = classify(status, body);
                switch (fault) {
                    case ARREARAGE -> {
                        // 账户级故障 · 所有模型一致 · 立刻 failover 到下一 vendor
                        log.warn("Qwen 账户级故障(欠费/账单过期)status={} · 立刻 failover", status);
                        throw new RuntimeException("Qwen 账户级故障(欠费/账单过期),failover", e);
                    }
                    case QUOTA_EXHAUSTED -> {
                        modelExhaustedUntil.put(model,
                                Instant.now().plusMillis(MODEL_EXHAUST_COOLDOWN_MS));
                        log.warn("Qwen 模型[{}] 免费额度用尽(status={}) · 冷却 {}h · 切下一模型",
                                model, status, MODEL_EXHAUST_COOLDOWN_MS / 3600_000);
                        // continue → 尝试下一个模型
                    }
                    default -> {
                        lastTransient = new RuntimeException(
                                "Qwen 模型[" + model + "] 调用失败 status=" + status, e);
                        recordTransientFailure();
                        // 限流/其它 4xx5xx → 也尝试下一个模型(可能换模型即可)
                    }
                }
            } catch (RuntimeException e) {
                // 超时 / 解析错 / null 等非 HTTP 错误 → transient
                lastTransient = e;
                recordTransientFailure();
            }
        }

        // 所有模型都用尽或失败
        if (!anyTried) {
            throw new RuntimeException("Qwen 所有模型均处于额度冷却,failover");
        }
        if (lastTransient != null) throw lastTransient;
        throw new RuntimeException("Qwen 所有模型免费额度均已用尽,failover");
    }

    /** 单模型单次调用 · 返回 content;HTTP 错误抛 {@link RestClientResponseException} */
    private String callOnce(String key, String model, String systemPrompt, String userPrompt) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(key);
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "temperature", 0.5,
                "max_tokens", currentMaxTokens()
        );

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

        String finishReason = (String) choices.get(0).get("finish_reason");
        if ("length".equals(finishReason)) {
            log.warn("Qwen[{}] 输出因 max_tokens 截断 · content.len={}", model, content.length());
        }
        return content;
    }

    private void recordTransientFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= FAIL_THRESHOLD) {
            breakerOpenedAt = Instant.now();
            log.warn("Qwen 连续 {} 次非额度类失败 · 短熔断 {}s", consecutiveFailures, COOLDOWN_MS / 1000);
        }
    }

    /** 故障分类:额度用尽(切模型)/ 账户级(failover)/ 其它(transient)。 */
    enum Fault { QUOTA_EXHAUSTED, ARREARAGE, TRANSIENT }

    static Fault classify(int status, String body) {
        String b = body == null ? "" : body.toLowerCase(Locale.ROOT);
        // 账户级 · 所有模型一致(欠费 / 账单过期)
        if (b.contains("arrearage") || b.contains("billoverdue")
                || b.contains("prepaid bill") || b.contains("postpaid bill")) {
            return Fault.ARREARAGE;
        }
        // 单模型免费额度用尽 → 切下一模型
        if (b.contains("allocationquota") || b.contains("insufficient_quota")
                || b.contains("freetieronly") || b.contains("free allocated quota")
                || (b.contains("free") && b.contains("quota") && b.contains("exhaust"))) {
            return Fault.QUOTA_EXHAUSTED;
        }
        return Fault.TRANSIENT;
    }
}
