package com.family.finance.domain.holdingimport;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * v1.4 · 持仓截图导入逐项:一支识别出的持仓 + 与已有持仓的三态匹配结果。
 *
 * <p>matchState:{@code UPDATE}(匹配更新 · matched_hid 命中,old_value→marketValue)/
 * {@code NEW}(新增)/ {@code SOLD}(库有本次没截到 · 默认 KEEP,用户可 ARCHIVE)。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HoldingImportItem {
    public static final String UPDATE = "UPDATE";
    public static final String NEW    = "NEW";
    public static final String SOLD   = "SOLD";
    public static final String KEEP    = "KEEP";
    public static final String ARCHIVE = "ARCHIVE";

    private Long id;
    private Long importId;
    private String parsedName;
    private String parsedCode;
    private BigDecimal marketValue;
    private String confidence;
    private String matchState;
    private Long matchedHid;
    private BigDecimal oldValue;
    private String assetClassTag;
    private String industryTag;
    private String platformTag;
    private String shotPath;
    private String userDecision;
    private Boolean selected;
    private Integer sortNo;
}
