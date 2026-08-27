package com.family.finance.domain.ask;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * v1.19 · 工具调用摘要。
 *
 * <p><b>只存摘要,不存返回体。</b>返回体里是完整的资产数据,再存一份等于把同一批数字
 * 多摊一个地方 —— 备份、导出、日志都得跟着多守一处。用户想知道「这个答案是查了什么得出的」,
 * 工具名 + 参数 + 耗时 + 成没成功已经够了;想看数就点引用块回原页。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AskToolCall {
    private Long id;
    private Long messageId;
    private String toolName;
    /** 参数摘要(截断到 1024)· 参数里没有敏感值,都是维度名和账期 */
    private String argsJson;
    private Integer durationMs;
    private boolean ok;

    /** 渲染期装配:给用户看的中文名(「资产分布」而不是 pivot) */
    private String label;
}
