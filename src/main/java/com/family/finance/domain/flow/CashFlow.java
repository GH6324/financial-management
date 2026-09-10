package com.family.finance.domain.flow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashFlow {
    private Long id;
    private Long periodId;
    private Long accountId;
    private CashFlowKind kind;
    private String categoryCode;
    private BigDecimal amount;
    private LocalDate occurredAt;
    private String note;
    private Long submittedBy;
    private LocalDateTime submittedAt;
    private boolean adjustment;   // v0.8 · is_adjustment · 账户内部现金调整(剔出投资损益)
    // v0.12 · 股票「+股数」收入的冲回信息 · 删除时按 refShares 冲回持仓股数;其它流水为 null
    private Long refHoldingId;
    private BigDecimal refShares;
    /** v1.18 · 这一笔是谁写进来的({@link com.family.finance.domain.ledger.LedgerSource});历史数据为 UNKNOWN = 当时没记 */
    private String sourceTag;

    /**
     * v1.21 · 消费分类(家庭自定义树)。
     *
     * <p><b>只在 {@code categoryCode == "consumption"} 时有意义。</b>
     * 「性质」(还贷 / 利息支出 / 转账给亲属)与「钱花在哪」(餐饮 / 交通)是<b>两个维度</b>:
     * 前者决定储蓄率与负债口径(AGENTS.md 联动链 L1),后者只回答消费构成。
     * 合并两者会静默把储蓄率算错 —— 那类错误不抛异常,只是数字慢慢不对。</p>
     *
     * <p>{@code null} = 未分类(v1.21 之前的历史流水,以及用户没选的)。</p>
     */
    private Long expenseCategoryId;

    /** v1.21 · 来自哪个导入批次;null = 手工录入。整批撤销靠它。 */
    private Long importBatchId;

    /** v1.21 · 渠道交易号,重复导入时用来跳过。故意不做 DB 唯一约束(见 V59 文件头)。 */
    private String extTxNo;
}
