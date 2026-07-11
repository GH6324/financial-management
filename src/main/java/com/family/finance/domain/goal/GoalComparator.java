package com.family.finance.domain.goal;

/** 目标达标方向 · v0.16。GTE=达到(增长类)· LTE=降到(负债/回撤等收敛类)。 */
public enum GoalComparator {
    GTE, LTE;

    public String label() { return this == LTE ? "≤ 降到" : "≥ 达到"; }

    public static GoalComparator fromOrDefault(String name) {
        if (name == null) return GTE;
        try { return valueOf(name.trim()); } catch (Exception e) { return GTE; }
    }
}
