package com.family.finance.domain.stock;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 股票账户持仓明细 · v0.3 FR-52。
 *
 * <p>持仓级 AUTO/MANUAL 混合模式 · 见 {@link ValuationMode}。
 * AUTO 模式要求 ticker / market / shares / currency 非空;
 * MANUAL 模式要求 manualValue 非空。Service 层做约束校验
 * (MySQL CHECK 跨厂商性差 · 不在 schema 层强制)。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockHolding {
    private Long id;
    private Long accountId;
    private String displayName;
    private ValuationMode valuationMode;

    // AUTO 字段
    private String ticker;
    private Market market;
    private BigDecimal shares;
    private BigDecimal costBasis;
    private String currency;

    // MANUAL 字段
    private BigDecimal manualValue;
    private LocalDateTime manualValueAt;

    /** v0.5 FR-78/79 · 是否由账户现金划转买入(归档时对称按市价把现金加回) */
    private Boolean cashLinked;

    // 通用
    private LocalDateTime archivedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
