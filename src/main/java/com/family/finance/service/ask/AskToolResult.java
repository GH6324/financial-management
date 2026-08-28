package com.family.finance.service.ask;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.19 · 一次工具调用的结果。
 *
 * <p><b>{@code meta} 里那四样是强制的</b>(账期 / 口径名 / 是否进行中 / 币种)——
 * 它们是引用块的原料,也是「数字说得清自己是哪一期」的载体。
 * 少一样,答案里的数字就重新变成一个说不清出处的裸数字,
 * 而那正是 v1.18 一整个系列在修的病。</p>
 *
 * <p>{@code citations} 是本次结果里<b>可被引用的数字</b>。模型正文只写
 * {@code {{cite:key}}},渲染时从这里取值 —— 模型<b>没有机会</b>打错数字。</p>
 */
public record AskToolResult(
        String tool,
        Map<String, Object> data,
        Map<String, Object> meta,
        List<Cite> citations,
        boolean ok,
        String error,
        /**
         * 一句话摘要:这一步<b>查到了什么</b>(「9 个平台 · 合计 …」)。
         *
         * <p>给思考过程那一栏用。只报工具名和耗时等于什么都没说 ——
         * 用户想看的是「它到底看了什么数」,那才是判断答案可不可信的依据。</p>
         */
        String summary
) {
    /** 一个可引用的数字 */
    public record Cite(String key, String metricKey, String label, String valueText,
                       Long periodId, boolean inProgress, String currency, String targetHref) {}

    public static Builder of(String tool) { return new Builder(tool); }

    public static AskToolResult failed(String tool, String error) {
        return new AskToolResult(tool, Map.of(), Map.of(), List.of(), false, error, null);
    }

    public static final class Builder {
        private final String tool;
        private final Map<String, Object> data = new LinkedHashMap<>();
        private final Map<String, Object> meta = new LinkedHashMap<>();
        private final List<Cite> cites = new java.util.ArrayList<>();
        private String summary;

        private Builder(String tool) { this.tool = tool; }

        public Builder put(String k, Object v) { data.put(k, v); return this; }

        /** 四样元数据一次给全 —— 分开传容易漏,漏了就退回「说不清哪一期」 */
        public Builder meta(Long periodId, String periodLabel, boolean inProgress,
                            String metricKey, String currency) {
            meta.put("periodId", periodId);
            meta.put("periodLabel", periodLabel);
            meta.put("inProgress", inProgress);
            meta.put("metricKey", metricKey);
            meta.put("currency", currency);
            return this;
        }

        public Builder metaExtra(String k, Object v) { meta.put(k, v); return this; }

        /** 这一步查到了什么,一句话。给思考过程那一栏看的,不进模型上下文 */
        public Builder summary(String s) { this.summary = s; return this; }

        public Builder cite(String key, String metricKey, String label, String valueText,
                            Long periodId, boolean inProgress, String currency, String href) {
            cites.add(new Cite(key, metricKey, label, valueText, periodId, inProgress, currency, href));
            return this;
        }

        public AskToolResult build() {
            return new AskToolResult(tool, data, meta, List.copyOf(cites), true, null, summary);
        }
    }
}
