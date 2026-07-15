package com.family.finance.calc.lens;

import java.util.List;
import java.util.Map;

/**
 * v1.1 · 统一多维查询网关的请求 spec(POST /lens/query 请求体 · 亦即 lens_board.spec_json)。
 *
 * <p>rows / cols = 维度 key(见 {@link LensRegistry});measures = 度量 key;
 * filters = 维度 key → 允许的标签值列表(含「未分类」)。旭日 = rows 两级 + cols 空。</p>
 */
public record LensQuery(
        List<String> rows,
        List<String> cols,
        List<String> measures,
        Map<String, List<String>> filters
) {
    public List<String> rowsSafe() { return rows == null ? List.of() : rows; }
    public List<String> colsSafe() { return cols == null ? List.of() : cols; }
    public List<String> measuresSafe() { return measures == null || measures.isEmpty() ? List.of("value") : measures; }
    public Map<String, List<String>> filtersSafe() { return filters == null ? Map.of() : filters; }
}
