package com.family.finance.domain.transfer;

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
public class Transfer {
    private Long id;
    private Long periodId;
    private Long fromAccountId;
    private Long toAccountId;
    private BigDecimal amount;
    private BigDecimal toAmount;   // v0.8 · 跨币种转账到账金额(转入账户币种);null=同币种
    private LocalDate occurredAt;
    private String note;
    private Long submittedBy;
    private LocalDateTime submittedAt;
    private boolean draft;
}
