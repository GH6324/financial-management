package com.family.finance.domain.account;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Account {
    private Long id;
    private Long familyId;
    private Long templateId;
    private String displayName;
    private AccountType type;
    private String currency;
    private Long primaryOwnerMemberId;
    private Long defaultPaymentSourceAccountId;
    private Integer displayOrder;
    private LocalDateTime archivedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** v0.2 · 产品类目 code(关联 product_category.code)· FR-40d */
    private String productCategoryCode;
    /** v0.2 · 用户覆盖类目默认风险等级(NULL = 沿用类目)· FR-40d */
    private Integer riskLevelOverride;
    /** v0.8 · 账户预期年化收益率 %(NULL = 回落品类 benchmark_pct)· 预实分析 FR-152 */
    private BigDecimal expectedReturnPct;

    /** v0.6 · 负债类型(MORTGAGE/CONSUMER/CREDIT_CARD/BORROW · 仅 LOAN · NULL=未填)· FR-103 */
    private String loanKind;
    /** v0.6 · 负债年利率 %(仅 LOAN · NULL=未填则资产负债表利率对照降级)· FR-103 */
    private BigDecimal annualRatePct;

    public boolean isArchived() {
        return archivedAt != null;
    }

    public AccountClass getAccountClass() {
        return type == AccountType.LOAN ? AccountClass.LIABILITY : AccountClass.ASSET;
    }

    public AccountLiquidity getLiquidity() {
        if (type == null) {
            return AccountLiquidity.NA;
        }
        return switch (type) {
            case CASH -> AccountLiquidity.LIQUID;
            case WEALTH, STOCK -> AccountLiquidity.SEMI_LIQUID;
            case PROPERTY -> AccountLiquidity.ILLIQUID;
            case LOAN, OTHER -> AccountLiquidity.NA;
        };
    }

    /**
     * v0.3.3 · 精细化流动性 · 优先 product_category.liquidityClass,fallback {@link #getLiquidity()}。
     *
     * <p>例:WEALTH 账户 + product=MONEY_FUND → LIQUID(否则被误判 SEMI_LIQUID)。</p>
     */
    public AccountLiquidity getLiquidity(String pcLiquidityClass) {
        if (pcLiquidityClass != null && !pcLiquidityClass.isBlank()) {
            try {
                return AccountLiquidity.valueOf(pcLiquidityClass.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // 数据脏 · 走兜底
            }
        }
        return getLiquidity();
    }
}
