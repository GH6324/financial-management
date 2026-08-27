package com.family.finance.service.ask;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * v1.19 · 工具定义的<b>唯一真相</b>。
 *
 * <p>OpenAPI 规格、MCP 的 tools 列表、以及将来任何一种接出方式,<b>全部由这里生成</b>。
 * 不允许「MCP 一份、REST 一份」——那正是本项目一整个 bug 家族的形状:
 * 同一件事两份判据,写下时都对,改的时候只改了一处。</p>
 *
 * <p>加工具只需实现 {@link AskTool} 并交给 Spring —— Spring 注入所有实现,这里自动收录。</p>
 */
@Component
@RequiredArgsConstructor
public class AskToolRegistry {

    private final List<AskTool> tools;

    /** 按名字取工具 */
    public Optional<AskTool> find(String name) {
        return tools.stream().filter(t -> t.name().equals(name)).findFirst();
    }

    public List<AskTool> all() {
        return tools.stream().sorted(java.util.Comparator.comparing(AskTool::name)).toList();
    }

    /**
     * MCP 的 {@code tools/list} 响应体。
     *
     * <p>与 OpenAPI 同源 —— 两边都从同一个 {@link AskTool#parameterSchema()} 出。</p>
     */
    public List<Map<String, Object>> mcpToolList() {
        return all().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("description", t.description());
            m.put("inputSchema", t.parameterSchema());
            return m;
        }).toList();
    }

    /**
     * OpenAI function-calling 形态的同一份清单(本地工具循环用)。
     *
     * <p>只是把 MCP 的 {@code inputSchema} 换个包装 —— <b>不是第二份定义</b>。
     * 护栏 {@code v119-ONE-TOOL-DEF} 盯着这条:两个方法必须遍历同一个 {@link #all()}。</p>
     */
    public List<Map<String, Object>> openAiToolList() {
        return all().stream().map(t -> Map.<String, Object>of(
                "type", "function",
                "function", Map.of(
                        "name", t.name(),
                        "description", t.description(),
                        "parameters", t.parameterSchema()))).toList();
    }

    /** 工具名 → 给用户看的中文短名(流式界面上显示「正在查资产分布」而不是 pivot) */
    public String displayName(String toolName) {
        return DISPLAY.getOrDefault(toolName, toolName);
    }

    /**
     * 中文短名表。
     *
     * <p>刻意<b>不</b>放进 {@link AskTool} 接口:那里的 {@code description} 是写给模型看的,
     * 要长、要讲清什么时候用;这里是写给用户看的,要短。两种受众塞进一个字段,
     * 结果一定是界面上出现一段给模型看的说明书。</p>
     */
    private static final Map<String, String> DISPLAY = Map.of(
            "capabilities", "看看能查什么",
            "pivot", "资产分布",
            "period_summary", "账期全貌",
            "account_performance", "账户收益");
}
