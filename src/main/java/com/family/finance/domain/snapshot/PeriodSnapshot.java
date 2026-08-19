package com.family.finance.domain.snapshot;

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
public class PeriodSnapshot {
    private Long id;
    private Long periodId;
    private Long accountId;
    private BigDecimal endBalance;
    private Long submittedBy;
    private LocalDateTime submittedAt;
    private String note;
    /** v1.18 · 这一行月末余额是谁写的({@link com.family.finance.domain.ledger.LedgerSource});历史数据为 UNKNOWN */
    private String sourceTag;
}
