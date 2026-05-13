package com.family.finance.domain.allocation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * v0.4 FR-62a · 资产配置锚模板实体。
 *
 * <p>4 行静态预置(标普 4321 / 雪球三分稳健 / 雪球三分激进 / 永久投资组合)+ CUSTOM 走 family JSON。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AllocationAnchor {
    private String code;
    private String displayName;
    private BigDecimal cashPct;
    private BigDecimal investPct;
    private BigDecimal propertyPct;
    private BigDecimal insurancePct;
    private String description;
    private Integer displayOrder;
}
