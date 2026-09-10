package com.family.finance.domain.expense;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * v1.21 · 一次导入的批次。<b>它本身就是审计日志</b> —— 渠道、行数、金额、被谁替换,都在这。
 *
 * <p>{@code replacedId} 串成替换链:同期同人同渠道重导时,新批次指向被它替换的那一批。
 * 于是「这个月的支付宝数据我导过几次、每次多少」在页面上可查,不需要另建日志表。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseImportBatch {
    private Long id;
    private Long familyId;
    private Long periodId;
    /** 整批落到同一个账户(FR-568)· 第一期不做多账户拆分 */
    private Long accountId;
    private ExpenseSource channel;
    private Integer rowCount;
    private BigDecimal totalAmount;
    /** 剔除/跳过的笔数 —— 「导入总额比账单少」时能解释清楚(FR-561) */
    private Integer droppedCount;
    private Integer skippedCount;
    private Long importedBy;
    private LocalDateTime importedAt;
    private LocalDateTime revokedAt;
}
