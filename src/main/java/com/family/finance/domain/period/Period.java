package com.family.finance.domain.period;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Period {
    private Long id;
    private Long familyId;
    private PeriodType periodType;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private PeriodStatus status;
    private LocalDateTime closedAt;
    private LocalDateTime createdAt;

    // v0.3 之前曾在此实体加 totalIncomeInput / totalExpenseInput(V15 家庭级 1 期 1 行)
    // 2026-05-13 修订:改为成员级 V19 period_member_cashflow 表 · 字段从此实体移除
    // V15 加的两列在 schema 保留为占位(prod 升级 0 影响 · 代码不引用)
}
