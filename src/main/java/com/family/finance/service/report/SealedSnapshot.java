package com.family.finance.service.report;

import com.family.finance.domain.period.Period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * v1.10 · 一个**封板期**的完整快照 —— 报表页一区(本期封板)与二区(结构与风险)的全部数据。
 *
 * <p>它由 {@link SealedPeriodService} 单一入口产出。**这个类型里没有任何 range 概念** ——
 * 从类型层面保证「切换时间范围不影响前两区」(FR-320),后人想把 range 传进来也没地方放。</p>
 */
public record SealedSnapshot(
        /** 锚定的期。closedSnapshot=false 时它是外壳锚(还没有任何已关账期)。 */
        Period period,
        boolean closedSnapshot,
        Completeness completeness,
        BalanceSheet balanceSheet,
        Waterfall waterfall,
        Comparison comparison,
        Concentration concentration,
        LiquidityTiers liquidity,
        Attribution attribution,
        /** 口径版本 · 抬头显示 · 见 {@link MetricFormulaVersion} */
        int formulaVersion,
        /** 无法定格的说明(如"账户类型/类目改过会改写历史分类")· 页面如实交代,不假装不存在 */
        List<String> driftNotes
) {

    /**
     * 紧急储备月数的展示规则 —— **两页共用这一份**。
     *
     * <p>超过体检的离群阈值(36 月)就显示 {@code > 36 月}:beta/真实数据里都出现过
     * 「月均支出极小 → 207.7 月」这种数,精确到 0.1 月毫无意义反而显得可疑。
     * 仪表盘原来有一份私有实现,v1.10 起委托到这里,避免两页对同一个数给不同写法
     * (prd v1.10 FR-322 验收 1 要求两页同名 KPI 口径与展示一致)。</p>
     */
    public static String emergencyLabel(BigDecimal months) {
        if (months == null) {
            return "—";
        }
        if (months.compareTo(com.family.finance.service.checkup.FamilyDiagnose.EMERGENCY_OUTLIER_MONTHS) > 0) {
            return "> 36 月";
        }
        return months.setScale(1, java.math.RoundingMode.HALF_EVEN).toPlainString() + " 月";
    }

    /** 还没有任何已关账期时的空壳:页面显示引导,不显示半截数字。 */
    public static SealedSnapshot unavailable(Period shellAnchor) {
        return new SealedSnapshot(shellAnchor, false, null, null, null, null, null, null, null,
                MetricFormulaVersion.CURRENT, List.of());
    }

    public boolean available() {
        return closedSnapshot && balanceSheet != null;
    }

    // ── FR-321 · 数据完整度 ────────────────────────────────────────────

    /**
     * 这份快照可信不可信的交代。
     *
     * @param filled       该期已填余额的账户数
     * @param expected     该期应填账户数
     * @param missingNames 未填账户名(最多前 3 个,给页面点名用)
     * @param autoValued   估值自动拉价的账户数
     * @param manualValued 手填的账户数
     * @param fxDate       该期汇率对应日期;null = 无外币账户,无需换算
     * @param closedAt     关账时间
     */
    public record Completeness(int filled, int expected, List<String> missingNames,
                               int autoValued, int manualValued,
                               LocalDate fxDate, java.time.LocalDateTime closedAt) {
        public boolean complete() {
            return expected > 0 && filled >= expected;
        }

        public int missingCount() {
            return Math.max(0, expected - filled);
        }
    }

    // ── FR-322 · 期末资产负债表 ────────────────────────────────────────

    /**
     * 六格 KPI。字段与仪表盘同名 KPI **同口径**(差异只能来自锚哪一期)。
     *
     * @param expenseWindowPeriods 月均支出窗口实际用到的期数(可能少于常量 —— 家庭还没那么多期)
     */
    public record BalanceSheet(BigDecimal netWorth, BigDecimal totalAssets, BigDecimal totalLiabilities,
                               BigDecimal debtToAssetRatio, BigDecimal liquidAssets,
                               BigDecimal emergencyFundMonths, BigDecimal avgExpense,
                               int expenseWindowPeriods) {

        /** 对称条左侧宽度占比(资产/(资产+负债))· 两侧同一比例尺 */
        public BigDecimal assetSharePct() {
            BigDecimal total = nz(totalAssets).add(nz(totalLiabilities).abs());
            if (total.signum() == 0) {
                return new BigDecimal("100.00");
            }
            return nz(totalAssets).multiply(new BigDecimal("100"))
                    .divide(total, 2, java.math.RoundingMode.HALF_EVEN);
        }

        private static BigDecimal nz(BigDecimal v) {
            return v == null ? BigDecimal.ZERO : v;
        }
    }

    // ── FR-323 · 资金流瀑布 ────────────────────────────────────────────

    /**
     * 期初 → +收入 → −支出 → ±投资损益 → 期末。
     *
     * <p>{@code identityDiff} 是恒等式差额:
     * {@code (期末 − 期初) − (收入 − 支出) − 投资损益}。
     * 闭合时约等于 0;不闭合时页面**如实显示差额与原因**,不假装闭合。</p>
     *
     * @param openingBaseline 本期首次出现账户的期末净值合计(外部资本纳入)· 差额的头号来源
     * @param axisLo          截断轴下界 · 见 {@link #axis()}
     */
    public record Waterfall(BigDecimal openNetWorth, BigDecimal income, BigDecimal expense,
                            BigDecimal investPnl, BigDecimal closeNetWorth,
                            BigDecimal identityDiff, BigDecimal openingBaseline,
                            BigDecimal axisLo, BigDecimal axisHi) {

        /** 容差 1 元(四舍五入噪声) */
        public boolean identityHolds() {
            return identityDiff != null && identityDiff.abs().compareTo(BigDecimal.ONE) <= 0;
        }

        /** 差额是否可由开账基线解释(页面据此选文案) */
        public boolean diffExplainedByOpening() {
            return !identityHolds() && openingBaseline != null
                    && identityDiff.subtract(openingBaseline).abs().compareTo(BigDecimal.ONE) <= 0;
        }

        /** 轴是否被截断(下界 > 0 就是截断了,页面必须明示) */
        public boolean axisTruncated() {
            return axisLo != null && axisLo.signum() > 0;
        }

        /** 四个拐点的累计值(给页面算柱高) */
        public List<BigDecimal> cumulative() {
            BigDecimal a = nz(openNetWorth);
            BigDecimal b = a.add(nz(income));
            BigDecimal c = b.subtract(nz(expense));
            BigDecimal d = nz(closeNetWorth);
            return List.of(a, b, c, d);
        }

        /** 轴高度(hi − lo)· 页面按它换算百分比 */
        public BigDecimal axis() {
            BigDecimal h = nz(axisHi).subtract(nz(axisLo));
            return h.signum() <= 0 ? BigDecimal.ONE : h;
        }

        private static BigDecimal nz(BigDecimal v) {
            return v == null ? BigDecimal.ZERO : v;
        }
    }

    // ── FR-324 · 三列对照 ──────────────────────────────────────────────

    /** 一行对照。{@code prev}/{@code yoy} 为 null 表示那一期不存在 → 页面显示 `—`,不显示 0 也不显示 100%。 */
    public record ComparisonRow(String label, BigDecimal current, BigDecimal prev, BigDecimal yoy,
                                boolean ratio, boolean lowerIsBetter) {

        /** 环比差额(比率类是 pp,金额类是绝对值);上期缺失 → null */
        public BigDecimal momDelta() {
            return (current == null || prev == null) ? null : current.subtract(prev);
        }

        public BigDecimal yoyDelta() {
            return (current == null || yoy == null) ? null : current.subtract(yoy);
        }

        /** 环比 %(仅金额类给);分母为 0 或缺期 → null(不给 ∞ 也不给 100%) */
        public BigDecimal momPct() {
            return pct(current, prev);
        }

        public BigDecimal yoyPct() {
            return pct(current, yoy);
        }

        private BigDecimal pct(BigDecimal cur, BigDecimal base) {
            if (ratio || cur == null || base == null || base.signum() == 0) {
                return null;
            }
            return cur.subtract(base).multiply(new BigDecimal("100"))
                    .divide(base.abs(), 2, java.math.RoundingMode.HALF_EVEN);
        }
    }

    /**
     * @param prevPeriod 上一个**已关账**期(不是 asof−1 个月 —— 可能跳期);null = 没有
     * @param yoyPeriod  去年**同月**的已关账期;null = 没有
     */
    public record Comparison(Period prevPeriod, Period yoyPeriod, List<ComparisonRow> rows) {
    }

    // ── FR-325a · 集中度 ──────────────────────────────────────────────

    /**
     * @param prev 上期的同名指标(画幽灵条);null = 没有上期 → 页面**不画**幽灵条(而不是画成 0)
     */
    public record Concentration(BigDecimal top1Pct, BigDecimal top3Pct, BigDecimal hhi,
                               BigDecimal topPlatformPct, String top1Name, String topPlatformName,
                               Concentration prev) {
    }

    // ── FR-325b · 流动性分层 ──────────────────────────────────────────

    /** 三档金额与占比;{@code NA} 档并入不可动。 */
    public record LiquidityTiers(BigDecimal liquid, BigDecimal semiLiquid, BigDecimal illiquid,
                                 BigDecimal liquidPct, BigDecimal semiLiquidPct, BigDecimal illiquidPct,
                                 BigDecimal coverMonths) {
    }

    // ── FR-326 · 本期归因 ─────────────────────────────────────────────

    public record Contribution(String accountName, BigDecimal amount, BigDecimal sharePct) {
    }

    /**
     * @param opened   本期首次出现的账户(资本纳入 —— **不进** positives,否则"补录存量账户"会显示成本月大赚)
     * @param archived 本期归档的账户(资本移出 —— **不进** negatives)
     */
    public record Attribution(List<Contribution> positives, List<Contribution> negatives,
                              List<Contribution> opened, List<Contribution> archived,
                              BigDecimal netWorthDelta) {
    }
}
