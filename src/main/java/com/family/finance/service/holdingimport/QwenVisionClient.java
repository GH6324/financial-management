package com.family.finance.service.holdingimport;

import com.family.finance.service.config.FamilyConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v1.4 · 持仓截图视觉转写客户端(qwen-vl-max)。
 *
 * <p>复用 Qwen / DashScope OpenAI 兼容端点 + 现有 Qwen key(不碰明文);模型由
 * {@link FamilyConfigService#K_LLM_VISION_MODEL} 配(默认 qwen-vl-max)。
 * <b>只转写屏幕可见的名称+市值+代码,绝不计算/推导</b>(承 feedback_llm_no_math)。
 * 打标(资产类型/行业/平台)交给 {@code LensAiTagService},此处不判。</p>
 */
@Component
@Slf4j
public class QwenVisionClient {

    private static final String API = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final long FAMILY_ID = 1L;   // 单家庭设计
    public static final String DEFAULT_MODEL = "qwen-vl-max";

    private static final String SYS =
        "你是持仓截图转写器。只转写图中肉眼可见的持仓,绝不计算、推导、反推或编造任何数值。"
        + "跳过分类汇总行(如「基金」「多宝理财」这种把下面几支加总的标题行),只要真正的单支持仓。输出严格 JSON,不要 markdown 围栏。";
    private static final String PROMPT =
        "这是一张理财/基金/券商 app 的持仓列表截图。逐支持仓转写,只读屏幕上肉眼可见的文字和数字。"
        + "输出一个 JSON 数组,每个元素:{\"name\":\"持仓名称(原样)\",\"code\":\"基金代码(6位数字,没有则 null)\","
        + "\"marketValue\":\"持仓市值/金额(原样字符串;读不到填 null)\",\"confidence\":\"high 或 low(名称/数字被遮挡或模糊则 low)\"}。"
        + "不要计算、不要合计、不要补全看不到的值。只输出 JSON 数组本身。";

    private final FamilyConfigService config;
    private final RestTemplate rt;

    public QwenVisionClient(FamilyConfigService config, RestTemplateBuilder builder) {
        this.config = config;
        this.rt = builder.setConnectTimeout(Duration.ofSeconds(10)).setReadTimeout(Duration.ofSeconds(90)).build();
    }

    private String apiKey() { return config.getString(FAMILY_ID, FamilyConfigService.K_LLM_QWEN_KEY, ""); }

    public String model() {
        String m = config.getString(FAMILY_ID, FamilyConfigService.K_LLM_VISION_MODEL, DEFAULT_MODEL);
        return (m == null || m.isBlank() || "off".equalsIgnoreCase(m)) ? DEFAULT_MODEL : m.trim();
    }

    /** 视觉可用 = Qwen key 已配 且 视觉模型未关闭 */
    public boolean available() {
        String key = apiKey();
        if (key == null || key.isBlank()) return false;
        String m = config.getString(FAMILY_ID, FamilyConfigService.K_LLM_VISION_MODEL, DEFAULT_MODEL);
        return m == null || !"off".equalsIgnoreCase(m.trim());
    }

    /** 一张图 → 若干持仓转写行。失败抛 RuntimeException(不泄 key)。 */
    public List<ParsedRow> extract(byte[] imageBytes, String mime) {
        String key = apiKey();
        if (key == null || key.isBlank()) throw new IllegalStateException("Qwen key 未配置");
        String dataUrl = "data:" + (mime == null ? "image/jpeg" : mime) + ";base64,"
                + java.util.Base64.getEncoder().encodeToString(imageBytes);
        String content = callVision(key, model(), dataUrl);
        return parse(content);
    }

    @SuppressWarnings("unchecked")
    private String callVision(String key, String model, String dataUrl) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(key);
        Map<String, Object> userMsg = Map.of("role", "user", "content", List.of(
                Map.of("type", "text", "text", PROMPT),
                Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))
        ));
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "system", "content", SYS), userMsg),
                "temperature", 0.1,
                "max_tokens", 1800
        );
        Map<String, Object> resp = rt.postForObject(API, new HttpEntity<>(body, h), Map.class);
        if (resp == null) throw new RuntimeException("视觉服务返回空");
        List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.get("choices");
        if (choices == null || choices.isEmpty()) throw new RuntimeException("视觉服务无结果");
        Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
        Object c = msg == null ? null : msg.get("content");
        if (c == null) throw new RuntimeException("视觉服务空内容");
        return c.toString();
    }

    /** 抽首个 JSON 数组(容忍 ```json 围栏)· 逐项取字段 · 市值只做格式规整(去逗号/币符),不做运算 */
    @SuppressWarnings("unchecked")
    static List<ParsedRow> parse(String raw) {
        List<ParsedRow> out = new ArrayList<>();
        if (raw == null) return out;
        int lb = raw.indexOf('['), rb = raw.lastIndexOf(']');
        if (lb < 0 || rb <= lb) return out;
        String json = raw.substring(lb, rb + 1);
        List<Map<String, Object>> arr;
        try {
            arr = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
        } catch (Exception e) {
            log.warn("vision JSON 解析失败: {}", e.toString());
            return out;
        }
        for (Map<String, Object> m : arr) {
            String name = str(m.get("name"));
            if (name == null || name.isBlank()) continue;
            out.add(new ParsedRow(
                    name.trim(),
                    str(m.get("code")),
                    parseMoney(str(m.get("marketValue"))),
                    normConfidence(str(m.get("confidence")))));
        }
        return out;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static String normConfidence(String c) {
        return "low".equalsIgnoreCase(c) ? "low" : "high";
    }

    private static final Pattern NUM = Pattern.compile("-?[0-9][0-9,]*(\\.[0-9]+)?");

    /** 「¥42,318.60」-> 42318.60 · 只去逗号/币符取数字(格式规整,非运算);读不到返回 null */
    static BigDecimal parseMoney(String s) {
        if (s == null || s.isBlank() || "null".equalsIgnoreCase(s.trim())) return null;
        Matcher mt = NUM.matcher(s);
        if (!mt.find()) return null;
        try {
            return new BigDecimal(mt.group().replace(",", ""));
        } catch (Exception e) {
            return null;
        }
    }

    /** 一支持仓的视觉转写结果(只名称+代码+市值+置信度,不含标签) */
    public record ParsedRow(String name, String code, BigDecimal marketValue, String confidence) {}
}
