package com.family.finance.service.checkup;

import com.family.finance.factview.AllocationSlice;
import com.family.finance.factview.KpiSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 全家体检 ViewModel · v0.2 · FR-40a
 *
 * 4 张诊断卡:
 * 1. 资产配置:按 AccountType 聚合
 * 2. 风险分布:按 product_category.risk_level 聚合(0/1/2/3/4/5/6)
 * 3. 流动性:紧急储备月数 + 流动资产占比
 * 4. 收益质量:家庭加权 XIRR / TWR / 当年累计损益
 */
public record FamilyDiagnose(
        KpiSnapshot kpi,
        List<AllocationSlice> allocation,
        List<RiskBucket> riskDistribution,
        BigDecimal liquidAssets,
        BigDecimal emergencyMonths,
        BigDecimal familyXirr,
        BigDecimal familyTwr,
        BigDecimal cumulativeYtdPnl,
        Integer accountCount,
        Integer pendingAccountCount
) {
    /** v1.6 UED review A7 · 超过此月数视为分母(月均支出)过小导致的失真,不再报数字 */
    public static final BigDecimal EMERGENCY_OUTLIER_MONTHS = new BigDecimal("36");

    /**
     * v1.6 UED review A7 · 异常值兜底。
     * 紧急储备 = 流动资产 ÷ 月均支出。月均支出接近 0 时会算出荒谬值(实测 723.0 个月 = 60 年),
     * 且与旁边「建议 ≥ 3 个月」并列,读起来像系统在胡说 —— 直接击穿指标可信度。
     * 超界一律收敛为语义值,并由 {@link #emergencyOutlier()} 驱动页面提示去补支出数据。
     */
    public String emergencyMonthsLabel() {
        if (emergencyMonths == null) return "—";
        if (emergencyOutlier()) return "> 36 个月";
        return emergencyMonths.setScale(1, RoundingMode.HALF_EVEN).toPlainString() + " 个月";
    }

    /** 月数失真(分母过小)· 页面据此提示「月均支出偏低,数据可能不完整」 */
    public boolean emergencyOutlier() {
        return emergencyMonths != null && emergencyMonths.compareTo(EMERGENCY_OUTLIER_MONTHS) > 0;
    }

    public String familyXirrPctLabel() {
        return pctLabel(familyXirr);
    }

    public String familyTwrPctLabel() {
        return pctLabel(familyTwr);
    }

    private static String pctLabel(BigDecimal v) {
        if (v == null) return "—";
        BigDecimal pct = v.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_EVEN);
        return (pct.signum() > 0 ? "+" : "") + pct.toPlainString() + "%";
    }

    public record RiskBucket(
            int level,            // 0=无 / 1-6
            String label,         // 「无风险」「极低」「低」「中低」「中」「中高」「极高」
            BigDecimal amount,    // 该桶总额(本位币)
            BigDecimal ratio      // 占总资产比例
    ) {
        public String stars() {
            if (level <= 0) return "—";
            return "★".repeat(Math.min(level, 6));
        }
    }
}
