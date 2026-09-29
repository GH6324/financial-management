package com.family.finance.service.checkup.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * v0.2 · LLM 全交互日志 · 2026-05-10
 *
 * <p><b>v1.28 起只记元数据</b>(模型、耗时、长度、指纹、结果),不再记提示词与回答正文(PRD FR-921)。
 * 原文存在 {@code llm_prompt_record},页面上点 {@code >_} 看。</p>
 *
 * <p>每次 LLM 调用(qwen / deepseek)都通过此 logger 输出多行块,便于:
 * <ul>
 *   <li>{@code journalctl -u finance | grep LLM_AUDIT} 过滤</li>
 *   <li>(v1.28 起不再记正文;逐字核对 prompt 在页面上点 >_)</li>
 *   <li>看每次调用的 elapsed_ms,排查 SLA</li>
 * </ul>
 *
 * <p>独立 logger name = {@code llm.audit},生产环境想关掉只需在 logback 配置里
 * 把 {@code <logger name="llm.audit" level="WARN"/>} 即可静音。
 */
public final class LlmAuditLogger {

    private static final Logger LOG = LoggerFactory.getLogger("llm.audit");

    private LlmAuditLogger() {}

    /**
     * 记录一次完整 LLM 调用。
     *
     * @param vendor      qwen / deepseek
     * @param scope       FAMILY / ACCOUNT
     * @param familyId    家庭 id
     * @param entityId    账户 id(scope=FAMILY 时为 null)
     * @param systemPrompt LLM system 消息全文
     * @param userPrompt  LLM user 消息全文
     * @param response    LLM 返回原始文本(可能为 null 表示调用抛异常)
     * @param elapsedMs   总耗时(含网络 + 推理)
     * @param accepted    OutputValidator 是否接受
     * @param rejectReason 不接受时的具体理由(为 null 表示接受)
     * @param error       调用抛异常时的消息(为 null 表示成功返回)
     */
    public static void log(String vendor, String scope, Long familyId, Long entityId,
                           String systemPrompt, String userPrompt,
                           String response, long elapsedMs,
                           boolean accepted, String rejectReason, String error) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append('\n');
        sb.append("===== LLM_AUDIT [").append(vendor).append("] ");
        sb.append("scope=").append(scope);
        sb.append(" family=").append(familyId);
        sb.append(" entity=").append(entityId == null ? "null" : entityId);
        sb.append(" elapsed=").append(elapsedMs).append("ms");
        sb.append(" SLA=").append(slaLabel(elapsedMs));
        sb.append(' ');
        if (error != null) {
            sb.append("ERROR ").append('\n');
            sb.append("--- error ---").append('\n');
            sb.append(error).append('\n');
        } else {
            sb.append("ok=").append(accepted);
            if (!accepted && rejectReason != null) sb.append(" reject=\"").append(rejectReason).append("\"");
            sb.append('\n');
        }
        // v1.28 FR-921 · 只记元数据:长度 + 指纹前 12 位。正文(含家里的金额)不进服务器日志 ——
        // 要看发出去的是什么,在页面上点 >_(发出去的原文存在 llm_prompt_record,只给本家庭看)。
        sb.append("system=").append(len(systemPrompt)).append("chars#").append(fp(systemPrompt))
          .append(" user=").append(len(userPrompt)).append("chars#").append(fp(userPrompt))
          .append(" response=").append(len(response)).append("chars").append('\n');
        sb.append("===== /LLM_AUDIT [").append(vendor).append("] =====");
        LOG.info(sb.toString());
    }

    private static int len(String s) { return s == null ? 0 : s.length(); }

    /** 内容指纹前 12 位(排障时对得上「是不是同一段」,又看不出内容) */
    private static String fp(String s) {
        if (s == null) return "-";
        return com.family.finance.service.llmtrace.PromptRecorder.sha256(s).substring(0, 12);
    }

    /** 简单 SLA 标签:< 3s OK / 3-8s SLOW / > 8s VERY_SLOW */
    private static String slaLabel(long ms) {
        if (ms < 3000) return "OK";
        if (ms < 8000) return "SLOW";
        return "VERY_SLOW";
    }
}
