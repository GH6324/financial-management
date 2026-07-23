package com.family.finance.domain.penetration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * v1.5 · 持仓方向(账户 → 持仓 → 持仓方向)· 融合打标与基金穿透的落点。
 *
 * <p>一支基金 = N 个方向(股票按行业 / 债 / 现金,各带权重)。无穿透的持仓不建方向行,
 * lens 回落隐式 1 条 100%(用持仓现有单标签)→ 老数据零迁移。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HoldingAllocation {
    private Long id;
    private Long holdingId;
    /** 万分比 · 同持仓合计 10000 */
    private Integer weightBp;
    /** L1 桶:EQUITY/FIXED_INCOME/CASH_EQ...(AssetClass.name()) */
    private String assetClass;
    /** 行业维值(仅权益有意义 · IndustryTag.name()) */
    private String industry;
    /** STOCK/BOND/CASH/OTHER */
    private String kind;
    /** PENETRATED/MANUAL/DEFAULT */
    private String source;
    /** 报告期 如 2025Q4 */
    private String reportPeriod;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static final String KIND_STOCK = "STOCK";
    public static final String KIND_BOND = "BOND";
    public static final String KIND_CASH = "CASH";
    public static final String KIND_OTHER = "OTHER";
    public static final String SRC_PENETRATED = "PENETRATED";
    public static final String SRC_MANUAL = "MANUAL";
    public static final String SRC_DEFAULT = "DEFAULT";
}
