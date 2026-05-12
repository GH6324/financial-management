package com.family.finance.domain.period;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 成员级月度收支聚合 · v0.3 FR-51 修订(2026-05-13)。
 *
 * <p>每个家庭成员每期一行 · 各自填自己的收支 ·
 * 家庭总额 = SUM(period_member_cashflow WHERE period_id=X)。</p>
 *
 * <p>取代 v0.3 早期 period.total_income_input / total_expense_input(家庭级 1 期 1 行)。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PeriodMemberCashflow {
    private Long id;
    private Long familyId;
    private Long periodId;
    private Long memberId;
    private BigDecimal totalIncomeInput;
    private BigDecimal totalExpenseInput;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
