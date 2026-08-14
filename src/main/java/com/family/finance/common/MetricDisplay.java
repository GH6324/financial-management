package com.family.finance.common;

import java.math.BigDecimal;

/**
 * v1.12 FR-353 · 比率类指标的「显示层降级」规则(唯一出处)。
 *
 * <h3>要修的是什么</h3>
 * v1.11 审计 F5:prod 收支数据稀疏(逐笔收支只有个位数行),某期收入 300、支出 7450,
 * 储蓄率 =(300 − 7450)÷ 300 = <b>−2383%</b>。
 * <b>口径没错,是分母太小</b> —— 但页面上摆一个 −2383% 会让人以为系统坏了。
 *
 * <h3>为什么是显示层降级,不是改口径</h3>
 * 改口径(比如给分母加下限、或把这类期排除)会污染真实数据:
 * 那一期的储蓄率<b>确实</b>是 −2383%,只是这个数字对用户没有信息量。
 * 所以计算侧一个字不动,只在<b>渲染时</b>把超阈值的值换成一句话 + 补录入口。
 * 这不是隐藏问题,是把「分母太小导致比率失真」这件事**说出来**,并告诉用户怎么让它变准。
 *
 * <h3>为什么阈值必须只有一处</h3>
 * 这条规则要作用在多个页面(仪表盘实时储蓄率 / 报表储蓄区 / 封板报表三列对照),
 * 每处各写一个 500% 字面量,迟早出现「A 页降级了 B 页没降级」的同屏矛盾
 * (v1.11 的 hx-select 落空、v0.14 加枚举漏模板硬编码都是同一类事故)。
 * 所以阈值 + 文案都钉在这个类里,护栏 {@code v112-RATIO-INSUFFICIENT} 盯着
 * 「除本类之外没有第二个 500% 阈值」。
 */
public final class MetricDisplay {

    private MetricDisplay() {
    }

    /**
     * 比率类指标的「荒谬值」阈值(绝对值 · 比率口径,5 = 500%)。
     *
     * <p>为什么是 500%:正常家庭的储蓄率落在 −100%~100%(支出是收入两倍 = −100%)。
     * 500% 已经远在「这个月支出是收入的 6 倍」之外 —— 到这个量级,数字反映的是
     * 分母(收入)没填全,不是家庭的储蓄行为。取整数便于用户理解,不追求精确临界点。</p>
     */
    public static final BigDecimal RATIO_ABSURD_ABS = new BigDecimal("5");

    /** 降级文案(KPI / 正文用 · 完整表述)。 */
    public static final String INSUFFICIENT = "收支数据不足";

    /**
     * 降级文案(表格单元格用 · 短表述)。
     *
     * <p>为什么留两种长度:封板报表的三列对照表一行有 6 列,单元格里塞完整文案会折行、
     * 把整张表撑歪(memory {@code feedback_sibling_uniform_selfcheck}:并列元素要同尺寸)。
     * 规则和阈值是同一个,只是长度两种 —— 不是两套判断。</p>
     */
    public static final String INSUFFICIENT_SHORT = "收支不足";

    /** 补录入口(用户点过去填收支的地方)。 */
    public static final String BACKFILL_HREF = "/entry";

    /** 补录入口文案。 */
    public static final String BACKFILL_LABEL = "补录收支";

    /**
     * 这个比率值是否已经失真到不该显示成数字。
     *
     * @param ratio 比率口径(0.48 = 48%),null = 没有数据(那是另一回事,显示 {@code —})
     */
    public static boolean ratioAbsurd(BigDecimal ratio) {
        return ratio != null && ratio.abs().compareTo(RATIO_ABSURD_ABS) > 0;
    }

    /**
     * 给模板用的文案包。
     *
     * <p>模板里<b>不许</b>再写 {@code '收支数据不足'} 这类字面量 —— 三个页面各写一遍,
     * 改文案时必漏一处。{@code GlobalModelAdvice} 把这个包注入所有 model,
     * 模板一律 {@code ${ratioNote.insufficient()}}。</p>
     */
    public record Note(String insufficient, String insufficientShort,
                       String backfillHref, String backfillLabel) {
    }

    public static final Note NOTE = new Note(INSUFFICIENT, INSUFFICIENT_SHORT, BACKFILL_HREF, BACKFILL_LABEL);
}
