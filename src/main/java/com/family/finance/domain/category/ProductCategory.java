package com.family.finance.domain.category;

import com.family.finance.domain.account.AccountType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * v0.2 产品类目 · 16 行 flyway 静态预置(V11),admin 可微调字段值。
 *
 * 关联:account.product_category_code → product_category.code
 * 用途:基准对照(benchmark_pct)+ 风险评级(risk_level)+ UI pill 显示
 *
 * 详见 PRD § FR-40d / TDD § 决策 4
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductCategory {
    /** 主键,如 CASH_DEPOSIT / A_STOCK / US_STOCK · 规则引擎可引用 */
    private String code;
    private String displayName;
    /** 0=无 / 1 极低 / 2 低 / 3 中低 / 4 中 / 5 中高 / 6 极高 */
    private Integer riskLevel;
    /** 流动性分级 · v0.3.3 引入 · LIQUID / SEMI_LIQUID / ILLIQUID / NA · admin 可微调 */
    private String liquidityClass;
    /** 基准指数标签,如「沪深 300」「标普 500 / QQQ」;NULL = 无稳定基准 */
    private String benchmarkLabel;
    /** 长期年化基准 % · 如 8.00 表示 8%;NULL = 无稳定基准 */
    private BigDecimal benchmarkPct;
    /** 逗号分隔的 AccountType 列表: "CASH,WEALTH" / "STOCK" / "*"(全部) */
    private String applicableTypes;
    private String description;
    private Integer displayOrder;

    /** 风险等级转 ★ 字符串 · UI pill 显示用 */
    public String riskStars() {
        if (riskLevel == null || riskLevel <= 0) return "—";
        return "★".repeat(Math.min(riskLevel, 6));
    }

    /** 是否有稳定基准 · 影响 UI 是否显示「基准对照」段 */
    public boolean hasBenchmark() {
        return benchmarkPct != null && benchmarkLabel != null;
    }

    /** 此类目是否适用于给定 AccountType */
    public boolean appliesTo(AccountType type) {
        if (applicableTypes == null || type == null) return false;
        if ("*".equals(applicableTypes.trim())) return true;
        for (String t : applicableTypes.split(",")) {
            if (t.trim().equalsIgnoreCase(type.name())) return true;
        }
        return false;
    }
}
