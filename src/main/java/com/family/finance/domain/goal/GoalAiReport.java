package com.family.finance.domain.goal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 目标 AI 月报 / 偏离预警 · v0.3 FR-53b/c。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GoalAiReport {
    private Long id;
    private Long goalId;
    private Long periodId;
    /** MONTHLY 或 ALERT */
    private String reportType;
    private String content;
    /** PASS / FAIL */
    private String validatorStatus;
    private LocalDateTime generatedAt;
    private LocalDateTime dismissedAt;
}
