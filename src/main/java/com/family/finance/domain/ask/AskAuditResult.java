package com.family.finance.domain.ask;

/**
 * v1.19 · 一次接口调用的判定结果。
 *
 * <p><b>对外全部返回 404 的那几种,在审计里必须分得开</b> ——
 * 用户要能看懂是「过期了」还是「填错了」,否则他只会看到一片红而不知道该做什么。</p>
 */
public enum AskAuditResult {
    /** 正常 */
    OK("正常"),
    /** 换绑完成后的新密钥 */
    OK_NEW("新密钥"),
    /** 换绑期间仍在用旧密钥 —— 页面据此催用户去百炼换完 */
    OK_OLD("仍在用旧密钥"),
    /** 凭据不匹配 */
    INVALID("口令不对"),
    /** 已过期 */
    EXPIRED("已过期"),
    /** 已吊销 */
    REVOKED("已吊销"),
    /** scope 不足 */
    SCOPE("权限不够"),
    /** 触发限流 */
    RATE("调用太频繁"),
    /** 功能未启用 */
    OFF("未启用");

    private final String label;
    AskAuditResult(String label) { this.label = label; }
    public String getLabel() { return label; }

    /** 是不是「通过」的那几种 */
    public boolean passed() { return this == OK || this == OK_NEW || this == OK_OLD; }
}
