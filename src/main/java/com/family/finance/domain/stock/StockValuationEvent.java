package com.family.finance.domain.stock;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * v0.4.1 FR-52f · 股票账户估值变动事件。
 *
 * <p>每次自动估值(cron / manual / 持仓变动)导致账户余额变化 > ¥0.01 时写一行。
 * ledger view 把它当第 4 种流水(VALUATION)显示。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockValuationEvent {
    private Long id;
    private Long familyId;
    private Long accountId;
    private Long periodId;
    private BigDecimal prevBalance;
    private BigDecimal newBalance;
    private BigDecimal delta;
    /** CRON · MANUAL · HOLDING_CHANGE */
    private String triggerKind;
    private Long triggeredByMemberId;
    private String note;
    private LocalDateTime triggeredAt;
}
