package com.family.finance.service.checkup.rule;

import com.family.finance.factview.AllocationSlice;
import com.family.finance.service.checkup.AccountDiagnose;
import com.family.finance.service.checkup.FamilyDiagnose;
import com.family.finance.service.config.FamilyConfigService;

import java.util.List;
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

    /** FAM-CON-1 · 单一 AccountType 占比 ≥ 50% → WARN · v1.27 分母跟随分析范围 */
    @Component
    public static class FamCon1TypeOverweight implements Rule {
        public String id() { return "FAM-CON-1"; }
        public Advice.Scope scope() { return Advice.Scope.FAMILY; }
        public Optional<Advice> evaluate(RuleContext ctx) {
            FamilyDiagnose f = ctx.family();
            if (f == null || f.allocation() == null || f.scopeEmpty()) return Optional.empty();
            for (AllocationSlice s : f.allocation()) {
                if (s.ratio() != null && s.ratio().compareTo(new BigDecimal("0.50")) >= 0) {
                    String pct = s.ratio().multiply(HUNDRED).setScale(0, RoundingMode.HALF_EVEN) + "%";
                    String name = s.label() == null ? s.accountType() : s.label().replace("\n", " ");
                    Advice a = Advice.of(
                            id(), Advice.Scope.FAMILY, null,
                            Advice.Dimension.RISK_ALLOCATION, Advice.Severity.WARN,
                            "类目集中度偏高",
                            name + " 占" + ctx.ratioDenominator() + " " + pct + ",已过半。" + ctx.scopeNote(),
                            "考虑分散至其他类目(债券 / 海外股 / 货币基金),平滑组合波动。",
                            "→ 看资产配置");
                    return Optional.of(markCta(a, s.accountType(), ctx));
                }
            }
            return Optional.empty();
        }

        /**
         * v1.27 FR-804 · 过线的是<b>房产类或其他类</b>时,行动按钮换成「标成不参与配置分析 →」。
         *
         * <p>这是这个能力的主入口:用户正是在看到「房产占九成」这张一直亮着的卡时,想起「这套房我不打算动」。
         * 同类只有一个账户 → 直达它的编辑页、落在勾选项上;有好几个 → 账户列表按这一类筛出来。</p>
         */
        static Advice markCta(Advice a, String accountType, RuleContext ctx) {
            if (!"PROPERTY".equals(accountType) && !"OTHER".equals(accountType)) return a;
            List<Long> ids = new java.util.ArrayList<>();
            if (ctx.accounts() != null) {
                for (AccountDiagnose d : ctx.accounts()) {
                    var acc = d.account();
                    if (acc != null && acc.getType() != null && accountType.equals(acc.getType().name())
                            && !acc.isAnalysisExcluded() && !acc.isArchived()) {
                        ids.add(acc.getId());
                    }
                }
            }
            String href = ids.size() == 1
                    ? "/accounts/" + ids.get(0) + "/edit#analysis-excluded"
                    : "/accounts?type=" + accountType + "&mark=analysis";
            return a.withCta("标成不参与配置分析 →", href);
        }
    }

    /** FAM-CON-2 · 仅 1 种 AccountType 有数据 → WARN(完全单极) */
    @Component
    public static class FamCon2SingleType implements Rule {
        public String id() { return "FAM-CON-2"; }
        public Advice.Scope scope() { return Advice.Scope.FAMILY; }
        public Optional<Advice> evaluate(RuleContext ctx) {
            FamilyDiagnose f = ctx.family();
            if (f == null || f.allocation() == null || f.scopeEmpty()) return Optional.empty();
            long nonZero = f.allocation().stream()
                    .filter(s -> s.value() != null && s.value().signum() > 0)
                    .count();
            // v1.27 · 一类都没有(没资产 / 范围为空)不是「过度单一」
            if (nonZero > 1 || nonZero == 0) return Optional.empty();
            return Optional.of(Advice.of(
                    id(), Advice.Scope.FAMILY, null,
                    Advice.Dimension.RISK_ALLOCATION, Advice.Severity.WARN,
                    "配置过度单一",
                    (ctx.analysisScope().isAll() ? "全家资产" : ctx.ratioDenominator()) + "集中于单一类目,缺乏分散。" + ctx.scopeNote(),
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
            if (f == null || f.riskDistribution() == null || f.scopeEmpty()) return Optional.empty();
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
                    "高风险类目(★★★★★及以上)合计占" + ctx.ratioDenominator() + " " + pct + ",超过推荐上限 " + thresholdPct + "。" + ctx.scopeNote(),
                    "建议将其中一部分调整至中低风险类目,降低组合波动率与最大回撤敞口。",
                    "→ 看风险分布"));
        }
    }

    /**
     * FAM-RISK-2 · 没有任何投资类资产 → INFO 提示资产仍可增长。
     *
     * <p>v1.27(PRD §13 ⑩)· 原来只认 STOCK / WEALTH —— 只持有加密或贵金属的家庭被当成「没有投资」。
     * 收口到 {@code AccountType.isInvestment()}(与 v1.18.5 的账户级规则同一处定义);
     * 只看范围内的账户(被标「不参与配置分析」的股票账户不算「有投资」—— 用户说了那部分不打算动)。</p>
     */
    @Component
    public static class FamRisk2AllConservative implements Rule {
        public String id() { return "FAM-RISK-2"; }
        public Advice.Scope scope() { return Advice.Scope.FAMILY; }
        public Optional<Advice> evaluate(RuleContext ctx) {
            FamilyDiagnose f = ctx.family();
            if (f == null || ctx.accounts() == null || f.scopeEmpty()) return Optional.empty();
            var scope = ctx.analysisScope();
            boolean hasInvestment = ctx.accounts().stream()
                    .filter(a -> a.account() != null && scope.includes(a.account().getId()))
                    .anyMatch(a -> a.account().getType() != null && a.account().getType().isInvestment());
            if (hasInvestment) return Optional.empty();
            // 仅当家庭已有一定现金资产时才提示(避免新家庭被打扰)
            if (f.kpi() == null || f.kpi().totalAssets() == null
                    || f.kpi().totalAssets().compareTo(new BigDecimal("100000")) < 0) return Optional.empty();
            return Optional.of(Advice.of(
                    id(), Advice.Scope.FAMILY, null,
                    Advice.Dimension.RETURN_QUALITY, Advice.Severity.INFO,
                    "资产仍有增长空间",
                    (scope.isAll() ? "全家" : ctx.ratioDenominator() + "里") + "暂未配置股票 / 理财 / 加密 / 贵金属这类投资资产,可能错过长期复利机会。" + ctx.scopeNote(),
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
            if (f == null || f.allocation() == null || f.scopeEmpty()) return Optional.empty();
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
                    "已分散至 " + nonZero + " 类资产,各类占比均 ≤ " + thresholdPct + "。" + ctx.scopeNote(),
                    "维持当前节奏,在新增资金时优先补强占比偏低的类目以保持均衡。",
                    null));
        }
    }
}
