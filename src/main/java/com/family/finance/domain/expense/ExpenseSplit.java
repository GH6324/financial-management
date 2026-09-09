package com.family.finance.domain.expense;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * v1.21 · 一条<b>来源行</b>:(账期 × 成员 × 类目 × 来源)唯一。
 *
 * <p>它不是「一笔支出」—— 本项目<b>永远不记逐笔流水</b>。它是「这个月、这个人、这个类目、
 * 从这个来源来的一个汇总数」。</p>
 *
 * <p>{@code amount} <b>只有 MANUAL 允许为负</b>:那是用户对导入值的冲正,
 * 保留导入行原样以便审计。类目的合成额(Σ来源行)不可为负,由 {@code ExpenseSplitService} 校验。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseSplit {
    private Long id;
    private Long familyId;
    private Long periodId;
    private Long memberId;
    private Long categoryId;
    private ExpenseSource source;
    private BigDecimal amount;
    private Long batchId;
    private LocalDateTime updatedAt;
}
