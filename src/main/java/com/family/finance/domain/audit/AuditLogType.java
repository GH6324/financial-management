package com.family.finance.domain.audit;

public enum AuditLogType {
    ACCOUNT_CREATE,
    ACCOUNT_UPDATE,
    ACCOUNT_ARCHIVE,
    ACCOUNT_RESTORE,
    FAMILY_UPDATE,
    PERIOD_OPEN,
    PERIOD_CLOSE,
    PERIOD_REOPEN,
    TRANSFER_CREATE,
    /** 现金流登记 / 修改 */
    CASH_FLOW_WRITE,
    /** 余额快照写入 / 覆盖 */
    SNAPSHOT_WRITE,
    /** 汇率拉取 / 手填 */
    FX_FETCH,
    /** 备份 success/fail */
    BACKUP,
    /** 密码重置 */
    PASSWORD_RESET,
    /** Logo 上传 */
    LOGO_UPLOAD,
    /** Metrics 重算 */
    METRICS_RECOMPUTE,
    /** CSV 一键导出 */
    EXPORT,
    /** v0.2 · LLM 文案润色全部 client 失败,降级到 raw 文案 */
    LLM_DEGRADED,
    /** v0.2 · 2026-05-10 修订 · LLM 综合诊断输出被 OutputValidator 拒绝(禁词 / 真名泄露 / 长度) */
    LLM_REJECTED,
    /** v0.15 · 券商关联(替换接管)前的持仓快照留痕,供不可回退操作找回 */
    BROKER_LINK,
    /** v1.15 FR-380 · 登录名变更 · summary 必须写清「旧名 → 新名」,否则事后没人对得上账 */
    MEMBER_RENAME,
    /** v1.15 FR-381 · 成员归档 */
    MEMBER_ARCHIVE,
    /** v1.15 FR-381 · 撤销归档 */
    MEMBER_RESTORE,
    /** v1.15 FR-383 · 成员物理删除(仅零引用时可达)· 这条记完,被删的人就再也查不到了 */
    MEMBER_DELETE,
    SYSTEM
}
