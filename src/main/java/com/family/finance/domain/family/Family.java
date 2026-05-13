package com.family.finance.domain.family;

import com.family.finance.domain.period.PeriodType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Family {
    private Long id;
    private String name;
    private String brandText;
    private String logoPath;
    /** v0.2 FR-1/FR-34:预设图标 code(icon1..icon4),默认 icon2。驱动 iOS apple-touch-icon + PWA manifest;web favicon/nav 在 logo_path 为空时用 */
    private String logoPreset;
    private String baseCurrency;
    private PeriodType periodType;
    /** v0.4 FR-61a · 通胀对照线假设值(% · 默认 2.00 = 2%) */
    private java.math.BigDecimal cpiAssumption;
    /** v0.4 FR-62a · 配置锚 code · 关联 allocation_anchor.code · 默认 SP_4321 · CUSTOM 走 allocationAnchorCustom */
    private String allocationAnchor;
    /** v0.4 FR-62a · 自定义锚 JSON {"cash":10,"invest":30,"property":40,"insurance":20} */
    private String allocationAnchorCustom;
    /** v0.4 FR-62b · 家庭风险偏好 · CONSERVATIVE / MODERATE / AGGRESSIVE · LLM 调仓 prompt 输入 */
    private String riskAppetite;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
