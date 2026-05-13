package com.family.finance.domain.allocation;

/**
 * v0.4 FR-62b · 家庭风险偏好枚举 · LLM 调仓 prompt 输入。
 */
public enum RiskAppetite {
    CONSERVATIVE,
    MODERATE,
    AGGRESSIVE;

    public static boolean isValid(String code) {
        if (code == null) return false;
        try {
            valueOf(code.trim().toUpperCase());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
