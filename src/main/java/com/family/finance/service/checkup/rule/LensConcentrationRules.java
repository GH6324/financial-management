package com.family.finance.service.checkup.rule;

import com.family.finance.calc.lens.LensQuery;
import com.family.finance.calc.lens.PivotEngine;
import com.family.finance.calc.lens.Position;
import com.family.finance.service.config.FamilyConfigService;
import com.family.finance.service.lens.LensQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * v1.1 · 基于资产透视维度的两条集中度体检规则(prd v1.1 FR-3/FR-7)。
 *
 * <p>吃 {@link LensQueryService} 头寸 + {@link PivotEngine}(与透视页同源同口径),
 * 阈值走 /admin/calc-tweaks 可配(承 feedback_admin_runtime_config)。
 * 「未分类」不触发预警(没打标 ≠ 集中);lens 组装异常时静默跳过,不拖垮体检。</p>
 */
public class LensConcentrationRules {

    private static final long FAMILY_ID = 1L;   // 单家庭模式 · 同 FamilyRules
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** 最大非未分类切片(label, sharePct) */
    private static Optional<Object[]> topSlice(List<Position> ps, String dim, Map<String, List<String>> filters) {
        PivotEngine.Result r = PivotEngine.pivot(ps, new LensQuery(List.of(dim), List.of(), List.of("value", "share"), filters));
        for (int i = 0; i < r.rowKeys().size(); i++) {          // 已按金额降序 · 未分类沉底
            String label = r.rowKeys().get(i).get(0);
            if (PivotEngine.UNCLASSIFIED.equals(label)) continue;
            BigDecimal share = r.rowTotals().get(i).get(1);
            return share == null ? Optional.empty() : Optional.of(new Object[]{label, share});
        }
        return Optional.empty();
    }

    /** LENS-CON-1 · 单一行业占权益 ≥ 阈值 → WARN */
    @Component
    @RequiredArgsConstructor
    public static class LensIndustryConcentration implements Rule {
        private final LensQueryService lensQueryService;
        private final FamilyConfigService configService;

        public String id() { return "LENS-CON-1"; }
        public Advice.Scope scope() { return Advice.Scope.FAMILY; }

        public Optional<Advice> evaluate(RuleContext ctx) {
            try {
                double threshold = configService.getDouble(FAMILY_ID,
                        FamilyConfigService.K_LENS_INDUSTRY_CONC, 0.40);
                var top = topSlice(lensQueryService.positions(FAMILY_ID), "industry",
                        Map.of("assetClass", List.of("股票股权")));
                if (top.isEmpty()) return Optional.empty();
                String label = (String) top.get()[0];
                BigDecimal share = (BigDecimal) top.get()[1];
                BigDecimal limit = BigDecimal.valueOf(threshold).multiply(HUNDRED);
                if (share.compareTo(limit) < 0) return Optional.empty();
                return Optional.of(Advice.of(
                        id(), Advice.Scope.FAMILY, null,
                        Advice.Dimension.RISK_ALLOCATION, Advice.Severity.WARN,
                        "行业过度集中",
                        "「" + label + "」占权益资产 " + share.setScale(0, java.math.RoundingMode.HALF_EVEN)
                                + "%(阈值 " + limit.setScale(0, java.math.RoundingMode.HALF_EVEN)
                                + "%)。单一行业波动会放大组合回撤。",
                        "建议分散:减配「" + label + "」,增配宽基或其它行业;到「透视 → 行业集中」看板逐层下钻定位具体持仓。",
                        "→ 去透视"));
            } catch (Exception e) {
                return Optional.empty();   // lens 数据不可用不拖垮体检
            }
        }
    }

    /** LENS-CON-2 · 单一平台占总资产 ≥ 阈值 → WARN */
    @Component
    @RequiredArgsConstructor
    public static class LensPlatformConcentration implements Rule {
        private final LensQueryService lensQueryService;
        private final FamilyConfigService configService;

        public String id() { return "LENS-CON-2"; }
        public Advice.Scope scope() { return Advice.Scope.FAMILY; }

        public Optional<Advice> evaluate(RuleContext ctx) {
            try {
                double threshold = configService.getDouble(FAMILY_ID,
                        FamilyConfigService.K_LENS_PLATFORM_CONC, 0.40);
                var top = topSlice(lensQueryService.positions(FAMILY_ID), "platform", Map.of());
                if (top.isEmpty()) return Optional.empty();
                String label = (String) top.get()[0];
                BigDecimal share = (BigDecimal) top.get()[1];
                BigDecimal limit = BigDecimal.valueOf(threshold).multiply(HUNDRED);
                if (share.compareTo(limit) < 0) return Optional.empty();
                return Optional.of(Advice.of(
                        id(), Advice.Scope.FAMILY, null,
                        Advice.Dimension.RISK_ALLOCATION, Advice.Severity.WARN,
                        "平台集中度偏高",
                        "总资产 " + share.setScale(0, java.math.RoundingMode.HALF_EVEN)
                                + "% 集中在「" + label + "」(阈值 "
                                + limit.setScale(0, java.math.RoundingMode.HALF_EVEN)
                                + "%)。平台 / 账户安全风险不宜过于集中。",
                        "建议把部分资产分散到其它机构;到「透视 → 平台安全」看板查看各平台占比与明细。",
                        "→ 去透视"));
            } catch (Exception e) {
                return Optional.empty();
            }
        }
    }
}
