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
    /** v1.4 · 截图导入触发的估值事件 → 指向 holding_import · ledger 可展开看导入明细+原图 */
    private Long refImportId;
    /**
     * v1.18 · 流水来源({@link com.family.finance.domain.ledger.LedgerSource})。
     *
     * <p>和 triggerKind 是两个维度:triggerKind 说"什么动作触发了这次估值"(定时 / 手动 / 持仓变了),
     * sourceTag 说"数字是从哪来的"(股价 / 金价 / 币价 / 富途 / 老虎 / 截图导入)。
     * 同一个 CRON 触发,拉股票和拉黄金对用户是两件事。</p>
     */
    private String sourceTag;
    private LocalDateTime triggeredAt;
}
