package com.family.finance.domain.goal;

/**
 * 目标类型 · v0.3 FR-50 系列。
 *
 * <ul>
 *   <li>RETIREMENT 退休 / FIRE · 进度口径 = 全资产</li>
 *   <li>EDUCATION  子女教育金 · 进度口径 = 全资产</li>
 *   <li>EMERGENCY  应急储备 · 进度口径 = 仅 CASH 类(流动性硬约束)</li>
 * </ul>
 */
public enum GoalType {
    RETIREMENT,
    EDUCATION,
    EMERGENCY
}
