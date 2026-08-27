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
}
