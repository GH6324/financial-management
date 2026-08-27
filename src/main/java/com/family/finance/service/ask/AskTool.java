package com.family.finance.service.ask;

import com.family.finance.domain.ask.AskScope;

import java.util.Map;

/**
 * v1.19 · 一个可被 agent 调用的工具。
 *
 * <p>实现类<b>只做三件事</b>:参数校验 → 调既有 service → 包上口径元数据。
 * <b>一行计算都不写。</b></p>
 *
 * <p>理由不是洁癖:本项目一整个 bug 家族的形状是「同一件事两份判据」(已归档 6 次)。
 * 工具层若自己算,那就是<b>第三份口径</b> —— agent 说的数会和页面对不上,
 * 而用户没法判断谁对。</p>
 */
public interface AskTool {

    /** 工具名(MCP tool name 与 OpenAPI operationId 都用它) */
    String name();

    /** 给模型看的描述 —— 写清楚比省 token 重要,描述清晰能减少它的试错次数 */
    String description();

    /** JSON Schema(参数) */
    Map<String, Object> parameterSchema();

    /** 需要的数据范围 */
    AskScope requiredScope();

    /**
     * 执行。
     *
     * @throws AskParamException 参数非法 —— 调用方应把「可用取值」回给模型让它改,而不是直接失败
     */
    AskToolResult execute(long familyId, Map<String, Object> args);

    /** 参数非法:带上「你可以用的是这些」,让模型能自我修正 */
    class AskParamException extends RuntimeException {
        private final transient Map<String, Object> allowed;
        public AskParamException(String message, Map<String, Object> allowed) {
            super(message);
            this.allowed = allowed == null ? Map.of() : allowed;
        }
        public Map<String, Object> getAllowed() { return allowed; }
    }
}
