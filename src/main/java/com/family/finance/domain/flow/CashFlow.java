package com.family.finance.domain.flow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashFlow {
    private Long id;
    private Long periodId;
    private Long accountId;
    private CashFlowKind kind;
    private String categoryCode;
    private BigDecimal amount;
    private LocalDate occurredAt;
    private String note;
    private Long submittedBy;
    private LocalDateTime submittedAt;
    private boolean adjustment;   // v0.8 · is_adjustment · 账户内部现金调整(剔出投资损益)
    // v0.12 · 股票「+股数」收入的冲回信息 · 删除时按 refShares 冲回持仓股数;其它流水为 null
    private Long refHoldingId;
    private BigDecimal refShares;
    /** v1.18 · 这一笔是谁写进来的({@link com.family.finance.domain.ledger.LedgerSource});历史数据为 UNKNOWN = 当时没记 */
    private String sourceTag;
}
