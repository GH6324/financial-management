package com.family.finance.service.explain;

import com.family.finance.domain.account.AccountClass;
import com.family.finance.factview.AccountPerformance;
import com.family.finance.factview.AllocationSlice;
import com.family.finance.factview.FactProjector;
import com.family.finance.factview.KpiSnapshot;
import com.family.finance.service.checkup.FamilyDiagnose;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 计算指标透明化 · v0.5.3。
 *
 * <p>把"计算型 KPI"(净资产 / 紧急储备 / 本月资产收益 / 月均收支 / 人赚钱赚 …)的
 * <b>真实中间数值</b>拼成一行说明,塞进 {@code _kpi-info} tooltip 的「口径」下方。
 * 用户点 ⓘ 不再只看到公式文字,而能看到「流动资产 ¥X ÷ 月均支出 ¥Y = Z 月」这样的实算。</p>
 *
 * <p>设计取舍:</p>
 * <ul>
 *   <li><b>纯展示层</b> · 只负责把已算好的 {@link BigDecimal} 格式化成串,不做任何业务计算 ——
 *       计算仍在 FactView / HouseholdCashflow 等服务里完成,本类只读结果。这样口径数值与
 *       页面 KPI 必然一致(同一份来源)。</li>
 *   <li><b>币种由调用方决定</b> · dashboard / reports KPI 区走 viewCurrency;checkup 与
 *       reports 储蓄区走家庭本位币(模板原本就硬编码 ¥)。避免一侧 view 一侧 base 漂移。</li>
 *   <li><b>不在 Thymeleaf 里做算术</b> · 历史上 SpEL 沙箱 ban 过 {@code T(BigDecimal)};
 *       一律服务端算好成串再传模板(见 [[feedback_thymeleaf_diagnosis]])。</li>
 * </ul>
 *
 * <p>XIRR / TWR 是迭代/几何解、无法写成单条四则算式;这类只展示<b>真实输入端点 + 解得值</b>,
 * 不伪造算术步骤(诚实原则)。</p>
 */
@Service
public class MetricExplainService {

    // ============================ 通用格式化(public · 可被 controller / 单测复用) ============================

    public String symbol(String ccy) {
        if (ccy == null) return "¥";
        return switch (ccy) {
            case "USD" -> "$";
            case "HKD" -> "HK$";
            default -> "¥";
        };
    }

    /** "¥1,234"(无小数 · 千分位)· null → "—" */
    public String money(String ccy, BigDecimal v) {
        if (v == null) return "—";
        return symbol(ccy) + new DecimalFormat("#,##0").format(v.setScale(0, RoundingMode.HALF_UP));
    }

    /** "+¥1,234" / "−¥1,234" / "¥0" · null → "—" */
    public String signedMoney(String ccy, BigDecimal v) {
        if (v == null) return "—";
        String body = symbol(ccy) + new DecimalFormat("#,##0").format(v.abs().setScale(0, RoundingMode.HALF_UP));
        if (v.signum() < 0) return "−" + body;
        if (v.signum() > 0) return "+" + body;
        return body;
    }

    /** 小数比率 ×100 → "+1.23%"(强制带符号 · 2 位)· null → "—" */
    public String pct2Signed(BigDecimal decimalRatio) {
        if (decimalRatio == null) return "—";
        return String.format(Locale.ROOT, "%+.2f%%", decimalRatio.doubleValue() * 100);
    }

    /** 已是百分点单位的值(如基准 5.4)→ "5.4%"(dp 位 · 不强制符号)· null → "—" */
    public String pctUnits(BigDecimal percentValue, int dp) {
        if (percentValue == null) return "—";
        return percentValue.setScale(dp, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /** 小数比率 ×100 → "48.0%"(dp 位 · 不强制符号)· null → "—" */
    public String pctFromRatio(BigDecimal decimalRatio, int dp) {
        if (decimalRatio == null) return "—";
        return decimalRatio.multiply(new BigDecimal("100")).setScale(dp, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /** 月数 "3.2" · null → "—" */
    public String months(BigDecimal v) {
        if (v == null) return "—";
        return v.setScale(1, RoundingMode.HALF_EVEN).toPlainString();
    }

    // ============================ dashboard(viewCurrency) ============================

    public Map<String, String> dashboard(KpiSnapshot k, List<AllocationSlice> allocation,
                                          List<AccountPerformance> accountRows, String ccy) {
        Map<String, String> m = new LinkedHashMap<>();
        if (k == null) return m;
        m.put("netWorth", netWorthCalc(ccy, k.totalAssets(), k.totalLiabilities(), k.netWorth()));
        m.put("totalAssets", totalAssetsCalc(ccy, allocation, k.totalAssets()));
        m.put("totalLiabilities", totalLiabilitiesCalc(ccy, accountRows, k.totalLiabilities()));
        m.put("emergency", emergencyCalc(ccy, k.liquidAssets(), k.avgExpense(), k.emergencyFundMonths()));
        m.put("monthlyPnl", monthlyPnlCalc(ccy, k.netWorth(), k.prevNetWorth(), k.lastNetInflow(),
                k.monthlyInvestReturnPct()));
        return m;
    }

    // ============================ checkup(家庭本位币) ============================

    public Map<String, String> checkup(FamilyDiagnose d, String ccy) {
        Map<String, String> m = new LinkedHashMap<>();
        if (d == null || d.kpi() == null) return m;
        KpiSnapshot k = d.kpi();
        m.put("netWorth", netWorthCalc(ccy, k.totalAssets(), k.totalLiabilities(), k.netWorth()));
        m.put("totalAssets", totalAssetsCalc(ccy, d.allocation(), k.totalAssets()));
        m.put("totalLiabilities",
                "仅 LOAN 类型账户期末余额绝对值合计 = " + money(ccy, k.totalLiabilities()));
        m.put("familyXirr", familyXirrCalc(ccy, k.netWorth(), d.familyXirr()));
        m.put("familyTwr", familyTwrCalc(d.familyTwr()));
        // 紧急储备:三项全取 KpiSnapshot 同源(d.emergencyMonths() == kpi.emergencyFundMonths()),
        // 保证「分子 ÷ 分母 = 结果」自洽且与 KPI 卡完全一致(勿引第二份 avgExpense 源,否则漂移)
        m.put("emergency", emergencyCalc(ccy, k.liquidAssets(), k.avgExpense(), k.emergencyFundMonths()));
        m.put("liquidAssets",
                "LIQUID 类目(CASH + 货币基金等)期末合计 = " + money(ccy, d.liquidAssets()));
        m.put("monthlyPnl", monthlyPnlCalc(ccy, k.netWorth(), k.prevNetWorth(), k.lastNetInflow(),
                k.monthlyInvestReturnPct()));
        m.put("ytdPnl",
                "本年逐月 (净资产变化 − 净流入) 累加 = " + signedMoney(ccy, d.cumulativeYtdPnl()));
        return m;
    }

    // ============================ reports(KPI 区 viewCurrency · 储蓄区 baseCurrency) ============================

    /**
     * @param viewCcy            报表 KPI 区币种(人赚/钱赚/XIRR/基准/TWR)
     * @param baseCcy            储蓄区币种(月均收支按本位币 PMC 存)
     * @param firstNetWorth      区间起始期净资产(基准点 · viewCcy)
     * @param lastNetWorth       区间期末净资产(viewCcy)
     * @param firstLabel         起始期标签
     * @param lastLabel          期末期标签
     * @param periodCount        区间总期数
     * @param decompCount        计入分解的期数(= 总期数 − 1 · 基准期不计)
     * @param familyXirr         家庭含收入 XIRR(小数)
     * @param familyTwr          家庭剔收入 TWR(小数)
     * @param cumulativeNetInflow 区间累计净流入(人赚 · viewCcy)
     * @param cumulativePnl       区间累计投资 PnL(钱赚 · viewCcy)
     * @param familyBenchmarkPct  家庭加权基准(百分点单位)
     * @param benchmarkAccountCount 计入加权的账户数
     * @param benchmarkTotalBalance 计入加权的总余额(viewCcy)
     * @param savingsAvailable   是否有储蓄填报数据
     * @param filledCount        近 12 月实际填报的月份数
     * @param totalMonths        回看窗口期数(通常 12)
     * @param sumIncome          已填月份收入合计(baseCcy)
     * @param sumExpense         已填月份支出合计(baseCcy)
     * @param avgIncome          月均收入(baseCcy)
     * @param avgExpense         月均支出(baseCcy)
     * @param latestIncome       最近一期收入(baseCcy)
     * @param latestExpense      最近一期支出(baseCcy)
     * @param savingsRate        最近一期储蓄率(小数)
     * @param savingsMedian      月储蓄能力中位(baseCcy)
     */
    public record ReportsMetricInputs(
            String viewCcy, String baseCcy,
            BigDecimal firstNetWorth, BigDecimal lastNetWorth, String firstLabel, String lastLabel,
            int periodCount, int decompCount,
            BigDecimal familyXirr, BigDecimal familyTwr,
            BigDecimal cumulativeNetInflow, BigDecimal cumulativePnl,
            BigDecimal familyBenchmarkPct, int benchmarkAccountCount, BigDecimal benchmarkTotalBalance,
            boolean savingsAvailable, int filledCount, int totalMonths,
            BigDecimal sumIncome, BigDecimal sumExpense, BigDecimal avgIncome, BigDecimal avgExpense,
            BigDecimal latestIncome, BigDecimal latestExpense, BigDecimal savingsRate, BigDecimal savingsMedian
    ) {}

    public Map<String, String> reports(ReportsMetricInputs in) {
        Map<String, String> m = new LinkedHashMap<>();
        if (in == null) return m;
        String v = in.viewCcy();

        // 家庭 XIRR(含收入)· 迭代解 → 展示真实现金流端点 + 解得值
        m.put("familyXirr",
                "资金流序列:期初净资产 −" + money(v, in.firstNetWorth())
                        + (in.firstLabel() != null ? "(" + in.firstLabel() + ")" : "")
                        + " → 各期外部净流入 → 期末净资产 +" + money(v, in.lastNetWorth())
                        + (in.lastLabel() != null ? "(" + in.lastLabel() + ")" : "")
                        + ",按 " + in.periodCount() + " 期数值求解年化 = " + pct2Signed(in.familyXirr()));

        // vs 基准(余额加权)
        m.put("benchmark",
                "按 " + in.benchmarkAccountCount() + " 个账户期末余额(合计 " + money(v, in.benchmarkTotalBalance())
                        + ")对各自类目长期基准加权 = " + pctUnits(in.familyBenchmarkPct(), 1)
                        + " · 非实时行情");

        // 资产年化 TWR(剔收入 · 几何)
        m.put("familyTwr", familyTwrCalc(in.familyTwr())
                + " · 本区间 " + in.decompCount() + " 期连乘");

        // 人赚 · 净流入(累计)
        m.put("netInflow",
                "自起始期" + (in.firstLabel() != null ? "(" + in.firstLabel() + ")" : "")
                        + "之后逐期(收入 − 支出)累加 = " + signedMoney(v, in.cumulativeNetInflow())
                        + " · 共 " + in.decompCount() + " 期计入(起始期为基准、不重复计)");

        // 钱赚 · 投资 PnL = ΔNetWorth − 净流入
        m.put("pnl",
                "(期末净资产 " + money(v, in.lastNetWorth()) + " − 起始净资产 " + money(v, in.firstNetWorth())
                        + ") − 净流入 " + signedMoney(v, in.cumulativeNetInflow())
                        + " = " + signedMoney(v, in.cumulativePnl()));

        // ---- 储蓄区(本位币 ¥)----
        if (in.savingsAvailable()) {
            String b = in.baseCcy();
            int n = in.filledCount();
            m.put("avgIncome",
                    "近 " + in.totalMonths() + " 月有填 " + n + " 个月 · 收入合计 " + money(b, in.sumIncome())
                            + " ÷ " + n + " = " + money(b, in.avgIncome()));
            m.put("avgExpense",
                    "近 " + in.totalMonths() + " 月有填 " + n + " 个月 · 支出合计 " + money(b, in.sumExpense())
                            + " ÷ " + n + " = " + money(b, in.avgExpense()));
            m.put("savingsRate",
                    "最近一期:(收入 " + money(b, in.latestIncome()) + " − 支出 " + money(b, in.latestExpense())
                            + ") ÷ 收入 " + money(b, in.latestIncome()) + " = " + pctFromRatio(in.savingsRate(), 1));
            m.put("savingsMedian",
                    "近 " + n + " 个填报月每月(收入 − 支出)排序取中位 = " + signedMoney(b, in.savingsMedian()));
            m.put("filledMonths",
                    "近 " + in.totalMonths() + " 期中实际填过收入/支出的有 " + n + " 期");
        }
        return m;
    }

    // ============================ 共用 builder ============================

    private String netWorthCalc(String ccy, BigDecimal assets, BigDecimal liabilities, BigDecimal netWorth) {
        return "总资产 " + money(ccy, assets) + " − 总负债 " + money(ccy, liabilities)
                + " = " + money(ccy, netWorth);
    }

    private String totalAssetsCalc(String ccy, List<AllocationSlice> allocation, BigDecimal total) {
        if (allocation == null || allocation.isEmpty()) {
            return "本期资产类账户期末余额合计 = " + money(ccy, total);
        }
        String items = allocation.stream()
                .filter(s -> s.value() != null && s.value().signum() != 0)
                .map(s -> typeShort(s.label()) + " " + money(ccy, s.value()))
                .collect(Collectors.joining(" · "));
        if (items.isEmpty()) {
            return "本期资产类账户期末余额合计 = " + money(ccy, total);
        }
        return items + "\n合计 = " + money(ccy, total);
    }

    private String totalLiabilitiesCalc(String ccy, List<AccountPerformance> accountRows, BigDecimal total) {
        if (accountRows != null && !accountRows.isEmpty()) {
            String items = accountRows.stream()
                    .filter(a -> a.accountType() != null
                            && FactProjector.classOf(a.accountType()) == AccountClass.LIABILITY
                            && a.currentValue() != null && a.currentValue().signum() != 0)
                    .map(a -> a.accountName() + " " + money(ccy, a.currentValue().abs()))
                    .collect(Collectors.joining(" · "));
            if (!items.isEmpty()) {
                return items + "\n合计 = " + money(ccy, total);
            }
        }
        return "仅 LOAN 类型账户期末余额绝对值合计 = " + money(ccy, total);
    }

    private String emergencyCalc(String ccy, BigDecimal liquid, BigDecimal avgExpense, BigDecimal months) {
        if (avgExpense == null || avgExpense.signum() <= 0) {
            return "月均支出为 0,暂无法计算紧急储备(先在填报页填月支出)";
        }
        return "流动资产 " + money(ccy, liquid) + " ÷ 月均支出 " + money(ccy, avgExpense)
                + " = " + months(months) + " 个月";
    }

    private String monthlyPnlCalc(String ccy, BigDecimal netWorth, BigDecimal prevNetWorth,
                                  BigDecimal netInflow, BigDecimal pctDecimal) {
        if (prevNetWorth == null || prevNetWorth.signum() <= 0) {
            return "上期净资产缺失或为 0,暂无法计算本月资产收益率";
        }
        return "(期末净资产 " + money(ccy, netWorth) + " − 期初 " + money(ccy, prevNetWorth)
                + " − 本月净流入 " + signedMoney(ccy, netInflow) + ") ÷ 期初 " + money(ccy, prevNetWorth)
                + " = " + pct2Signed(pctDecimal);
    }

    private String familyXirrCalc(String ccy, BigDecimal netWorth, BigDecimal xirr) {
        return "含工资的资金加权 IRR:期初投入与各期工资视为现金流出、当前净资产 "
                + money(ccy, netWorth) + " 视为流入,数值求解年化 = " + pct2Signed(xirr);
    }

    private String familyTwrCalc(BigDecimal twr) {
        return "逐月「(期末 − 净流入) ÷ 期初」回报连乘后开 N 次方 − 1(剔除工资)= " + pct2Signed(twr);
    }

    /** "现金\n(CASH)" → "现金" */
    private String typeShort(String label) {
        if (label == null) return "";
        int nl = label.indexOf('\n');
        return nl < 0 ? label : label.substring(0, nl);
    }
}
