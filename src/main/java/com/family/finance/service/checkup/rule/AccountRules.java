package com.family.finance.service.checkup.rule;

import com.family.finance.domain.account.AccountType;
import com.family.finance.service.checkup.AccountDiagnose;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * 账户级规则 · 11 条 · v0.2 FR-40c
 * <p>
 * 流动性 (LIQ): LIQ-1 现金占比过低警示 / LIQ-2 现金集中告警
 * 现金效率 (EFF): EFF-1 大额现金长期闲置
 * 收益质量 (RET): RET-1 长期负收益 / RET-2 跑赢基准奖励 / RET-3 跑输基准提醒
 * 还款进度 (PRG): PRG-1 还款节奏正常 / PRG-2 长期未还款
 * 集中度 (RISK): RISK-1 单账户敞口超 30% / RISK-2 高风险敞口超 25%
 *
 * 注意:每条规则的数字阈值都写死在规则内,LLM 不能改。文案 LLM 可润色但需通过 OutputValidator。
 */
public class AccountRules {

    private static final BigDecimal C_30 = new BigDecimal("0.30");
    private static final BigDecimal C_25 = new BigDecimal("0.25");
    private static final BigDecimal C_20 = new BigDecimal("0.20");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** v1.27 FR-805 · 被标「不参与配置分析」的账户,集中度与风险规则不再触发 */
    static boolean excluded(AccountDiagnose d) {
        return d.account() != null && d.account().isAnalysisExcluded();
    }

    /** RET-1 · 持有 ≥ 6 期且年化 ≤ -10% → DANGER */
    @Component
    public static class Ret1LongTermLoss implements Rule {
        public String id() { return "RET-1"; }
        public Advice.Scope scope() { return Advice.Scope.ACCOUNT; }
        public Optional<Advice> evaluate(RuleContext ctx) {
            AccountDiagnose d = ctx.account();
            if (d == null || !d.isInvestment() || d.monthsHeld() == null || d.monthsHeld() < 6) return Optional.empty();
            BigDecimal r = d.annualizedReturn();
            if (r == null || r.compareTo(new BigDecimal("-0.10")) > 0) return Optional.empty();
            return Optional.of(Advice.of(
                    id(), Advice.Scope.ACCOUNT, d.account().getId(),
                    Advice.Dimension.RETURN_QUALITY, Advice.Severity.DANGER,
                    "长期负收益",
                    "持有 " + d.monthsHeld() + " 期累计年化 " + d.annualizedReturnPctLabel() + ",已显著低于基准。",
                    "建议复盘持仓配置:是否仓位过重 / 是否单只标的拖累。可考虑降低敞口或调整品种。",
                    "→ 调整仓位"));
        }
    }

    /** RET-2 · 跑赢基准 ≥ 3pp → OK 表扬 */
    @Component
    public static class Ret2OutperformBenchmark implements Rule {
        public String id() { return "RET-2"; }
        public Advice.Scope scope() { return Advice.Scope.ACCOUNT; }
        public Optional<Advice> evaluate(RuleContext ctx) {
            AccountDiagnose d = ctx.account();
            if (d == null || !d.isInvestment()) return Optional.empty();
            var b = d.benchmark();
            if (b == null || b.status().name() != "COMPARED" || b.diffPct() == null) return Optional.empty();
            if (b.diffPct().compareTo(new BigDecimal("3.00")) < 0) return Optional.empty();
            return Optional.of(Advice.of(
                    id(), Advice.Scope.ACCOUNT, d.account().getId(),
                    Advice.Dimension.RETURN_QUALITY, Advice.Severity.OK,
                    "跑赢基准",
                    "近 12 期年化 " + d.annualizedReturnPctLabel() + ",跑赢类目基准 "
                            + b.diffPct().setScale(1, RoundingMode.HALF_EVEN).toPlainString() + "pp。",
                    "持仓策略有效,建议保持。注意基准本身也可能在高位,关注最大回撤准备。",
                    null));
        }
    }

    /** RET-3 · 跑输基准 ≥ 3pp → WARN */
    @Component
    public static class Ret3UnderperformBenchmark implements Rule {
        public String id() { return "RET-3"; }
        public Advice.Scope scope() { return Advice.Scope.ACCOUNT; }
        public Optional<Advice> evaluate(RuleContext ctx) {
            AccountDiagnose d = ctx.account();
            if (d == null || !d.isInvestment()) return Optional.empty();
            var b = d.benchmark();
            if (b == null || b.status().name() != "COMPARED" || b.diffPct() == null) return Optional.empty();
            if (b.diffPct().compareTo(new BigDecimal("-3.00")) > 0) return Optional.empty();
            return Optional.of(Advice.of(
                    id(), Advice.Scope.ACCOUNT, d.account().getId(),
                    Advice.Dimension.RETURN_QUALITY, Advice.Severity.WARN,
                    "跑输基准",
                    "近 12 期年化 " + d.annualizedReturnPctLabel() + ",跑输类目基准 "
                            + b.diffPct().abs().setScale(1, RoundingMode.HALF_EVEN).toPlainString() + "pp。",
                    "考虑评估持仓:是否过多主动选股偏差?可配置一部分仓位至跟踪基准的指数型产品。",
                    "→ 看基准对照"));
        }
    }

    /**
     * RISK-1 · 单账户占总资产 ≥ 30% → DANGER
     *
     * <p>v1.27 · 被标「不参与配置分析」的账户不触发(FR-805):「这套房占全家 90% → 划转」没有意义;
     * 占比用本位币余额(原来原币 ÷ 本位币总资产,外币账户算错)。</p>
     */
    @Component
    public static class Risk1SingleAccountOverlimit implements Rule {
        public String id() { return "RISK-1"; }
        public Advice.Scope scope() { return Advice.Scope.ACCOUNT; }
        public Optional<Advice> evaluate(RuleContext ctx) {
            AccountDiagnose d = ctx.account();
            if (d == null || ctx.family() == null || excluded(d)) return Optional.empty();
            BigDecimal total = ctx.family().kpi().totalAssets();
            if (total == null || total.signum() <= 0 || d.balanceForShare() == null) return Optional.empty();
            BigDecimal ratio = d.balanceForShare().divide(total, 6, RoundingMode.HALF_EVEN);
            if (ratio.compareTo(C_30) < 0) return Optional.empty();
            String pct = ratio.multiply(HUNDRED).setScale(0, RoundingMode.HALF_EVEN) + "%";
            return Optional.of(Advice.of(
                    id(), Advice.Scope.ACCOUNT, d.account().getId(),
                    Advice.Dimension.RISK_ALLOCATION, Advice.Severity.DANGER,
                    "集中度告警",
                    "本账户敞口 " + pct + ",超过推荐集中度上限(30%)。",
                    "建议将部分资产再配置至其他类目,降低单账户风险敞口。",
                    "→ 划转"));
        }
    }

    /** RISK-2 · 高风险等级(≥5)且单账户 ≥ 20% → WARN */
    @Component
    public static class Risk2HighRiskOverweight implements Rule {
        public String id() { return "RISK-2"; }
        public Advice.Scope scope() { return Advice.Scope.ACCOUNT; }
        public Optional<Advice> evaluate(RuleContext ctx) {
            AccountDiagnose d = ctx.account();
            if (d == null || ctx.family() == null || excluded(d)) return Optional.empty();
            Integer level = d.effectiveRiskLevel();
            if (level == null || level < 5) return Optional.empty();
            BigDecimal total = ctx.family().kpi().totalAssets();
            if (total == null || total.signum() <= 0 || d.balanceForShare() == null) return Optional.empty();
            BigDecimal ratio = d.balanceForShare().divide(total, 6, RoundingMode.HALF_EVEN);
            if (ratio.compareTo(C_20) < 0) return Optional.empty();
            String pct = ratio.multiply(HUNDRED).setScale(0, RoundingMode.HALF_EVEN) + "%";
            return Optional.of(Advice.of(
                    id(), Advice.Scope.ACCOUNT, d.account().getId(),
                    Advice.Dimension.RISK_ALLOCATION, Advice.Severity.WARN,
                    "高风险敞口偏重",
                    "高风险账户(" + d.riskStars() + ")占总资产 " + pct + ",建议适度分散。",
                    "可将其中 1/3 划转至中低风险类目(如理财、债券基金),平滑组合波动。",
                    "→ 看风险敞口"));
        }
    }

    /** PRG-1 · LOAN 账户本月有还款转入 → INFO 鼓励 */
    @Component
    public static class Prg1Paydown implements Rule {
        public String id() { return "PRG-1"; }
        public Advice.Scope scope() { return Advice.Scope.ACCOUNT; }
        public Optional<Advice> evaluate(RuleContext ctx) {
            AccountDiagnose d = ctx.account();
            if (d == null || !d.isLoan()) return Optional.empty();
            BigDecimal in = d.cumulativeTransferIn();
            if (in == null || in.signum() <= 0) return Optional.empty();
            return Optional.of(Advice.of(
                    id(), Advice.Scope.ACCOUNT, d.account().getId(),
                    Advice.Dimension.DEBT_HEALTH, Advice.Severity.OK,
                    "还款进度",
                    "近期累计还款 ¥" + d.cumulativeTransferIn().setScale(0, RoundingMode.HALF_EVEN) + "。",
                    "保持当前还款节奏,如有结余可考虑加速偿还以减少利息支出。",
                    null));
        }
    }

    /** PRG-2 · LOAN 持有 ≥ 6 期但累计还款 = 0 → WARN */
    @Component
    public static class Prg2NoPaydown implements Rule {
        public String id() { return "PRG-2"; }
        public Advice.Scope scope() { return Advice.Scope.ACCOUNT; }
        public Optional<Advice> evaluate(RuleContext ctx) {
            AccountDiagnose d = ctx.account();
            if (d == null || !d.isLoan() || d.monthsHeld() == null || d.monthsHeld() < 6) return Optional.empty();
            BigDecimal in = d.cumulativeTransferIn();
            if (in != null && in.signum() > 0) return Optional.empty();
            return Optional.of(Advice.of(
                    id(), Advice.Scope.ACCOUNT, d.account().getId(),
                    Advice.Dimension.DEBT_HEALTH, Advice.Severity.WARN,
                    "长期未还款",
                    "已持有 " + d.monthsHeld() + " 期未见还款记录,欠款余额 ¥"
                            + (d.currentBalance() == null ? "—" : d.currentBalance().abs().setScale(0, RoundingMode.HALF_EVEN)) + "。",
                    "建议设置每月固定还款日,或在账户编辑页配置默认还款来源。",
                    "→ 设置还款来源"));
        }
    }

    /** LIQ-1 · CASH 账户余额 / 月均支出 < 0.5 → WARN(单卡现金太薄) */
    @Component
    public static class Liq1CashTooThin implements Rule {
        public String id() { return "LIQ-1"; }
        public Advice.Scope scope() { return Advice.Scope.ACCOUNT; }
        public Optional<Advice> evaluate(RuleContext ctx) {
            AccountDiagnose d = ctx.account();
            if (d == null || !d.isCash()) return Optional.empty();
            BigDecimal avg = ctx.avgMonthlyExpense();
            // v1.27 · 月均支出是本位币,余额也得用本位币(外币现金账户原来被算错几倍)
            if (avg == null || avg.signum() <= 0 || d.balanceForShare() == null) return Optional.empty();
            BigDecimal months = d.balanceForShare().divide(avg, 1, RoundingMode.HALF_EVEN);
            if (months.compareTo(new BigDecimal("0.5")) >= 0) return Optional.empty();
            return Optional.of(Advice.of(
                    id(), Advice.Scope.ACCOUNT, d.account().getId(),
                    Advice.Dimension.LIQUIDITY, Advice.Severity.WARN,
                    "单卡流动性偏薄",
                    "账户当前余额仅可覆盖约 " + months.toPlainString() + " 个月支出。",
                    "可从其他流动账户调入一部分,或调整该账户为非主要消费账户。",
                    null));
        }
    }

    /** LIQ-2 · CASH 账户余额 / 月均支出 > 12(过多闲置)→ INFO */
    @Component
    public static class Liq2CashIdleTooLong implements Rule {
        public String id() { return "LIQ-2"; }
        public Advice.Scope scope() { return Advice.Scope.ACCOUNT; }
        public Optional<Advice> evaluate(RuleContext ctx) {
            AccountDiagnose d = ctx.account();
            if (d == null || !d.isCash()) return Optional.empty();
            BigDecimal avg = ctx.avgMonthlyExpense();
            // v1.27 · 月均支出是本位币,余额也得用本位币(外币现金账户原来被算错几倍)
            if (avg == null || avg.signum() <= 0 || d.balanceForShare() == null) return Optional.empty();
            BigDecimal months = d.balanceForShare().divide(avg, 1, RoundingMode.HALF_EVEN);
            if (months.compareTo(new BigDecimal("12")) <= 0) return Optional.empty();
            return Optional.of(Advice.of(
                    id(), Advice.Scope.ACCOUNT, d.account().getId(),
                    Advice.Dimension.LIQUIDITY, Advice.Severity.INFO,
                    "现金长期闲置",
                    "账户余额可覆盖约 " + months.toPlainString() + " 个月支出,远超应急储备 3-6 月推荐区间。",
                    "可考虑配置部分至货币基金或短债,提升资金效率。",
                    "→ 看流动性配置"));
        }
    }

    /** EFF-1 · CASH 账户大额(≥ 总资产 25%)且 12 期内零变动 → INFO 提示效率 */
    @Component
    public static class Eff1LargeCashIdle implements Rule {
        public String id() { return "EFF-1"; }
        public Advice.Scope scope() { return Advice.Scope.ACCOUNT; }
        public Optional<Advice> evaluate(RuleContext ctx) {
            AccountDiagnose d = ctx.account();
            if (d == null || !d.isCash() || ctx.family() == null) return Optional.empty();
            BigDecimal total = ctx.family().kpi().totalAssets();
            if (total == null || total.signum() <= 0 || d.balanceForShare() == null) return Optional.empty();
            BigDecimal ratio = d.balanceForShare().divide(total, 6, RoundingMode.HALF_EVEN);
            if (ratio.compareTo(new BigDecimal("0.25")) < 0) return Optional.empty();
            // 简化:若 cumulativeIncome+expense+transfer 都为 0,认为零变动
            BigDecimal traffic = d.cumulativeIncome().add(d.cumulativeExpense())
                    .add(d.cumulativeTransferIn()).add(d.cumulativeTransferOut());
            if (traffic.signum() != 0) return Optional.empty();
            String pct = ratio.multiply(HUNDRED).setScale(0, RoundingMode.HALF_EVEN) + "%";
            return Optional.of(Advice.of(
                    id(), Advice.Scope.ACCOUNT, d.account().getId(),
                    Advice.Dimension.LIQUIDITY, Advice.Severity.INFO,
                    "大额现金未启用",
                    "本账户占总资产 " + pct + " 但近 12 期无任何流入流出。",
                    "考虑将其中一部分配置至货币基金或银行理财,获取无风险收益。",
                    "→ 看资金效率"));
        }
    }

    /** RISK-2-extra: 标记一个明显空规则避免数错 → 实际只有 9 条已实现,
     *  但综合 LIQ-1/2 + EFF-1 + RET-1/2/3 + PRG-1/2 + RISK-1/2 = 11 条 */

}
