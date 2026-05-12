package com.family.finance.domain.goal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 家庭财务目标 · v0.3 FR-50。
 *
 * <p>三类目标(RETIREMENT / EDUCATION / EMERGENCY)共用此实体,
 * 类型特定参数存在 {@link #paramsJson},Service 层用 Jackson 反序列化到 GoalParams 子类。</p>
 *
 * <p>EMERGENCY 类型的 {@link #targetValue} / {@link #targetDate} 为 NULL,
 * 目标值由 params(months_target × baseline)derived 计算。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Goal {
    private Long id;
    private Long familyId;
    private GoalType goalType;
    private String name;
    private BigDecimal targetValue;
    private LocalDate targetDate;
    private String paramsJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime archivedAt;
}
