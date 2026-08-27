package com.family.finance.domain.ask;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * v1.19 · 引用块 —— 这一版最关键的一张表。
 *
 * <p>模型的正文里<b>不写数字</b>,只写 {@code {{cite:c1}}}。真正的数值存在这里,
 * 来源是工具返回的原值。于是「模型把 760 万说成 706 万」这类错误<b>在结构上不可能发生</b> ——
 * 它没有机会碰那个数字。</p>
 *
 * <p>每个引用块都带着 {@code periodId} 与 {@code inProgress}:一个数字必须说得清
 * 「哪一期的、关账了没有」。进行中的期,渲染时会自动挂上提醒 —— 不依赖模型记得说。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AskCitation {
    private Long id;
    private Long messageId;
    /** 与正文 {{cite:c1}} 里的 c1 对应 */
    private String citeKey;
    /** 口径标识;渲染期据此向 MetricExplainService 取「这个数怎么算的」 */
    private String metricKey;
    private Long periodId;
    private boolean inProgress;
    /** 工具返回的原值(已格式化)—— 显示的就是它 */
    private String valueText;
    private String currency;
    /** 点一下回到哪一页,让用户能自己核 */
    private String targetHref;

    /** 渲染期装配:口径一句话说明(不入库,随版本走) */
    private String explain;
    /** 渲染期装配:这个数字的中文名 */
    private String label;
}
