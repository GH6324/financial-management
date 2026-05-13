package com.family.finance.domain.allocation;

/**
 * v0.4 FR-62a · 资产配置锚 code 枚举。
 *
 * <p>前 4 个对应 allocation_anchor 表预置静态行;CUSTOM 走 family.allocation_anchor_custom JSON。</p>
 */
public enum AnchorCode {
    SP_4321,
    XQ_CONSERVATIVE,
    XQ_AGGRESSIVE,
    PERMANENT,
    CUSTOM;

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
