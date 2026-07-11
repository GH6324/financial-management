package com.family.finance.domain.goal;

/**
 * 目标类型(= kind)· v0.3 FR-50 起,v0.16 加 CUSTOM。
 *
 * <ul>
 *   <li>RETIREMENT 退休 / FIRE · 智能预设 · 进度口径 = 全资产</li>
 *   <li>EDUCATION  子女教育金 · 智能预设 · 进度口径 = 全资产</li>
 *   <li>EMERGENCY  应急储备 · 智能预设 · 进度口径 = 仅 CASH 类(流动性硬约束)</li>
 *   <li>CUSTOM     自定义追踪 · v0.16 · 绑 0–N 账户 + 任一 {@link GoalMetric} + 目标值 + 时间范围</li>
 * </ul>
 *
 * <p>预设三类保留各自的参数向导 / 目标值推导 / 三情景 / AI 月报;CUSTOM 走通用 evaluator。</p>
 */
public enum GoalType {
    RETIREMENT,
    EDUCATION,
    EMERGENCY,
    CUSTOM;

    /** 是否为智能预设(带参数推导/三情景);CUSTOM 之外都是。 */
    public boolean isPreset() { return this != CUSTOM; }
}
