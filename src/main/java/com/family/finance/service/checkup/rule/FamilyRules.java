package com.family.finance.service.checkup.rule;

import com.family.finance.factview.AllocationSlice;
import com.family.finance.service.checkup.AccountDiagnose;
import com.family.finance.service.checkup.FamilyDiagnose;
import com.family.finance.service.config.FamilyConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * 全家级规则 · 6 条 · v0.2 FR-40c
 * <p>
 * FAM-LIQ-1 紧急储备月数不足 / FAM-CON-1 单类目占比过高 / FAM-CON-2 配置过度集中
 * FAM-RISK-1 高风险敞口超 40% / FAM-RISK-2 全家无任何投资
 * FAM-ALC-1 配置基本健康 (OK)
 */
public class FamilyRules {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    /** 单家庭模式 · 见 prd §22.3 类 A */
    private static final long FAMILY_ID = 1L;

    /** FAM-LIQ-1 · 紧急储备 < 3 个月 → DANGER */
    @Component
    public static class FamLiq1EmergencyShort implements Rule {
        public String id() { return "FAM-LIQ-1"; }
        public Advice.Scope scope() { return Advice.Scope.FAMILY; }
        public Optional<Advice> evaluate(RuleContext ctx) {
            FamilyDiagnose f = ctx.family();
            if (f == null || f.emergencyMonths() == null) return Optional.empty();
            if (f.emergencyMonths().compareTo(new BigDecimal("3")) >= 0) return Optional.empty();
            return Optional.of(Advice.of(
                    id(), Advice.Scope.FAMILY, null,
                    Advice.Dimension.LIQUIDITY, Advice.Severity.DANGER,
                    "应急储备不足",
                    "当前流动资产仅可覆盖 " + f.emergencyMonthsLabel() + ",低于推荐的 3 个月安全线。",
                    "建议优先补足应急储备:从理财类账户调拨一部分至活期 / 货币基金,达到至少 3 个月支出。",
                    "→ 看流动性"));
        }
    }

    /** FAM-CON-1 · 单一 AccountType 占比 ≥ 50% → WARN */
    @Component
    public static class FamCon1TypeOverweight implements Rule {
        public String id() { return "FAM-CON-1"; }
        public Advice.Scope scope() { return Advice.Scope.FAMILY; }
        public Optional<Advice> evaluate(RuleContext ctx) {
            FamilyDiagnose f = ctx.family();
            if (f == null || f.allocation() == null) return Optional.empty();
            for (AllocationSlice s : f.allocation()) {
                if (s.ratio() != null && s.ratio().compareTo(new BigDecimal("0.50")) >= 0) {
                    String pct = s.ratio().multiply(HUNDRED).setScale(0, RoundingMode.HALF_EVEN) + "%";
                    String name = s.label() == null ? s.accountType() : s.label().replace("\n", " ");
                    return Optional.of(Advice.of(
                            id(), Advice.Scope.FAMILY, null,
                            Advice.Dimension.RISK_ALLOCATION, Advice.Severity.WARN,
                            "类目集中度偏高",
                            name + " 占总资产 " + pct + ",已过半。",
                            "考虑分散至其他类目(债券 / 海外股 / 货币基金),平滑组合波动。",
                            "→ 看资产配置"));
                }
            }
            return Optional.empty();
        }
    }

    /** FAM-CON-2 · 仅 1 种 AccountType 有数据 → WARN(完全单极) */
    @Component
    public static class FamCon2SingleType implements Rule {
        public String id() { return "FAM-CON-2"; }
        public Advice.Scope scope() { return Advice.Scope.FAMILY; }
        public Optional<Advice> evaluate(RuleContext ctx) {
            FamilyDiagnose f = ctx.family();
            if (f == null || f.allocation() == null) return Optional.empty();
            long nonZero = f.allocation().stream()
                    .filter(s -> s.value() != null && s.value().signum() > 0)
                    .count();
            if (nonZero > 1) return Optional.empty();
            return Optional.of(Advice.of(
                    id(), Advice.Scope.FAMILY, null,
                    Advice.Dimension.RISK_ALLOCATION, Advice.Severity.WARN,
                    "配置过度单一",
                    "全家资产集中于单一类目,缺乏分散。",
                    "建议分配 30% 以上至差异化资产(如低相关性的债券 / 黄金 / 海外股票),提升组合韧性。",
                    "→ 看资产配置"));
        }
    }

    /** FAM-RISK-1 · 高风险敞口(level≥5)超过阈值 → DANGER · v0.4.18 阈值改读 ConfigService(默认 0.40) */
    @Component
    @RequiredArgsConstructor
    public static class FamRisk1HighRiskOver40 implements Rule {
        private final FamilyConfigService configService;
        public String id() { return "FAM-RISK-1"; }
        public Advice.Scope scope() { return Advice.Scope.FAMILY; }
        public Optional<Advice> evaluate(RuleContext ctx) {
            FamilyDiagnose f = ctx.family();
            if (f == null || f.riskDistribution() == null) return Optional.empty();
            BigDecimal high = f.riskDistribution().stream()
                    .filter(b -> b.level() >= 5)
                    .map(FamilyDiagnose.RiskBucket::ratio)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            double threshold = configService.getDouble(FAMILY_ID,
                    FamilyConfigService.K_CHECKUP_HIGH_RISK, 0.40);
            BigDecimal thresholdBd = BigDecimal.valueOf(threshold);
            if (high.compareTo(thresholdBd) < 0) return Optional.empty();
            String pct = high.multiply(HUNDRED).setScale(0, RoundingMode.HALF_EVEN) + "%";
            String thresholdPct = thresholdBd.multiply(HUNDRED).setScale(0, RoundingMode.HALF_EVEN) + "%";
            return Optional.of(Advice.of(
                    id(), Advice.Scope.FAMILY, null,
                    Advice.Dimension.RISK_ALLOCATION, Advice.Severity.DANGER,
                    "高风险敞口过大",
                    "高风险类目(★★★★★及以上)合计占总资产 " + pct + ",超过推荐上限 " + thresholdPct + "。",
                    "建议将其中一部分调整至中低风险类目,降低组合波动率与最大回撤敞口。",
                    "→ 看风险分布"));
        }
    }

    /** FAM-RISK-2 · 完全没有 STOCK / WEALTH 资产 → INFO 提示资产仍可增长 */
    @Component
    public static class FamRisk2AllConservative implements Rule {
        public String id() { return "FAM-RISK-2"; }
        public Advice.Scope scope() { return Advice.Scope.FAMILY; }
        public Optional<Advice> evaluate(RuleContext ctx) {
            FamilyDiagnose f = ctx.family();
            if (f == null || ctx.accounts() == null) return Optional.empty();
            boolean hasInvestment = ctx.accounts().stream()
                    .anyMatch(a -> a.account().getType().name().equals("STOCK")
                            || a.account().getType().name().equals("WEALTH"));
            if (hasInvestment) return Optional.empty();
            // 仅当家庭已有一定现金资产时才提示(避免新家庭被打扰)
            if (f.kpi() == null || f.kpi().totalAssets() == null
                    || f.kpi().totalAssets().compareTo(new BigDecimal("100000")) < 0) return Optional.empty();
            return Optional.of(Advice.of(
                    id(), Advice.Scope.FAMILY, null,
                    Advice.Dimension.RETURN_QUALITY, Advice.Severity.INFO,
                    "资产仍有增长空间",
                    "全家暂未配置 STOCK / WEALTH 类资产,可能错过长期复利机会。",
                    "可从总资产 5%-10% 起步,配置低费率指数基金或货币基金,逐步建立增长仓位。",
                    null));
        }
    }

    /** FAM-ALC-1 · 至少 3 类资产 + 各类占比 ≤ 集中度阈值 → OK 表扬 · v0.4.18 阈值改读 ConfigService(默认 0.40) */
    @Component
    @RequiredArgsConstructor
    public static class FamAlc1HealthyAllocation implements Rule {
        private final FamilyConfigService configService;
        public String id() { return "FAM-ALC-1"; }
        public Advice.Scope scope() { return Advice.Scope.FAMILY; }
        public Optional<Advice> evaluate(RuleContext ctx) {
            FamilyDiagnose f = ctx.family();
            if (f == null || f.allocation() == null) return Optional.empty();
            long nonZero = f.allocation().stream()
                    .filter(s -> s.value() != null && s.value().signum() > 0)
                    .count();
            if (nonZero < 3) return Optional.empty();
            double threshold = configService.getDouble(FAMILY_ID,
                    FamilyConfigService.K_CHECKUP_CONCENTRATION, 0.40);
            BigDecimal thresholdBd = BigDecimal.valueOf(threshold);
            boolean overweight = f.allocation().stream()
                    .anyMatch(s -> s.ratio() != null && s.ratio().compareTo(thresholdBd) > 0);
            if (overweight) return Optional.empty();
            String thresholdPct = thresholdBd.multiply(HUNDRED).setScale(0, RoundingMode.HALF_EVEN) + "%";
            return Optional.of(Advice.of(
                    id(), Advice.Scope.FAMILY, null,
                    Advice.Dimension.RISK_ALLOCATION, Advice.Severity.OK,
                    "配置基本健康",
                    "已分散至 " + nonZero + " 类资产,各类占比均 ≤ " + thresholdPct + "。",
                    "维持当前节奏,在新增资金时优先补强占比偏低的类目以保持均衡。",
                    null));
        }
    }
}
