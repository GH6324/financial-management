package com.family.finance.domain.goal;

/** 目标时间模式 · v0.16。OPEN=长期不设期限(旧三类)· DEADLINE=有截止日(1/3/5年或自定义)。 */
public enum TimeMode {
    OPEN, DEADLINE;

    public static TimeMode fromOrDefault(String name) {
        if (name == null) return OPEN;
        try { return valueOf(name.trim()); } catch (Exception e) { return OPEN; }
    }
}
