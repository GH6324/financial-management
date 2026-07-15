package com.family.finance.service.lens;

import com.family.finance.domain.lens.AssetClass;
import com.family.finance.domain.lens.IndustryTag;
import com.family.finance.service.checkup.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * v1.1 · AI 推荐打标(prd v1.1 FR-1b · 已拍板 D4)。
 *
 * <p><b>只做分类,不做数学</b>(承 feedback_llm_no_math 允许域):把账户名 / 持仓名交给现有
 * LLM 接入,推荐 平台 / 行业 / 大类。<b>白名单校验</b>:industry / assetClass 必须是枚举
 * name(),枚举外值直接丢弃(OutputValidator 同风格);platform 自由文本截断 40。
 * 结果仅作<b>待确认预填</b>,用户显式保存才落库(承贷款预填「显式接受」教训)。
 * 全部 client 不可用 → {@link #available()} false,入口降级隐藏,手动打标不受影响。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LensAiTagService {

    private final List<LlmClient> clients;
    private final ObjectMapper objectMapper;

    public boolean available() {
        return clients.stream().anyMatch(LlmClient::available);
    }

    /** 单条建议(枚举 name 或 null) */
    public record Tags(String platform, String industry, String assetClass) {}

    /**
     * 名称 → 建议标签。任一 client 成功即返回;全失败返回空 map(页面提示稍后再试)。
     */
    public Map<String, Tags> suggest(List<String> names) {
        if (names == null || names.isEmpty()) return Map.of();
        String industries = java.util.Arrays.stream(IndustryTag.values())
                .map(t -> t.name() + "=" + t.getLabel()).collect(Collectors.joining(", "));
        String classes = java.util.Arrays.stream(AssetClass.values())
                .map(c -> c.name() + "=" + c.getLabel()).collect(Collectors.joining(", "));
        String system = """
                你是家庭记账软件的资产分类助手。只做分类,不做任何计算。
                对每个资产名称推断三个标签:platform(平台/机构中文名,如 招商银行/支付宝 · 蚂蚁财富/富途证券,不确定给 null)、
                industry(必须取枚举 code:%s,不确定给 null)、
                assetClass(必须取枚举 code:%s,不确定给 null)。
                只输出一个严格 JSON 对象,键=输入名称原文,值={"platform":...,"industry":...,"assetClass":...}。
                不要输出任何其它文字、注释或 markdown。""".formatted(industries, classes);
        String user;
        try {
            user = objectMapper.writeValueAsString(names);
        } catch (Exception e) {
            return Map.of();
        }
        for (LlmClient client : clients) {
            if (!client.available()) continue;
            try {
                String raw = client.chat(system, user);
                Map<String, Tags> out = parseAndWhitelist(raw, names);
                if (!out.isEmpty()) return out;
            } catch (Exception e) {
                log.warn("AI 打标 vendor={} 失败: {}", client.vendor(), e.toString());
            }
        }
        return Map.of();
    }

    /** 提取首个 {...} → 解析 → 白名单过滤(枚举外丢弃 · platform 截 40) */
    Map<String, Tags> parseAndWhitelist(String raw, List<String> names) {
        if (raw == null) return Map.of();
        int s = raw.indexOf('{'), e = raw.lastIndexOf('}');
        if (s < 0 || e <= s) return Map.of();
        Map<String, Tags> out = new HashMap<>();
        try {
            JsonNode root = objectMapper.readTree(raw.substring(s, e + 1));
            for (String name : names) {
                JsonNode n = root.get(name);
                if (n == null || !n.isObject()) continue;
                String platform = text(n, "platform");
                if (platform != null && platform.length() > 40) platform = platform.substring(0, 40);
                String industry = text(n, "industry");
                if (IndustryTag.fromName(industry) == null) industry = null;   // 白名单外丢弃
                String assetClass = text(n, "assetClass");
                if (AssetClass.fromName(assetClass) == null) assetClass = null;
                if (platform != null || industry != null || assetClass != null) {
                    out.put(name, new Tags(platform,
                            industry == null ? null : industry.trim().toUpperCase(),
                            assetClass == null ? null : assetClass.trim().toUpperCase()));
                }
            }
        } catch (Exception ex) {
            return Map.of();
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return s == null || s.isBlank() || "null".equalsIgnoreCase(s) ? null : s.trim();
    }
}
