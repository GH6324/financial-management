package com.family.finance.service.analysis;

import com.family.finance.calc.BalanceSheetHealth;
import com.family.finance.calc.BenchmarkComparator;
import com.family.finance.calc.ConcentrationCalculator;
import com.family.finance.calc.RebalanceDrift;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.factview.AllocationSlice;
import com.family.finance.factview.KpiSnapshot;
import com.family.finance.service.checkup.AccountDiagnose;
import com.family.finance.service.checkup.FamilyDiagnose;
import com.family.finance.service.checkup.llm.PromptBuilder;
import com.family.finance.service.checkup.rule.Advice;
import com.family.finance.service.insight.AssetInsight;
import com.family.finance.service.insight.InsightPromptBuilder;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.27 · 提示词零差异基线的<b>固定材料</b>(PRD §6「零差异基线」· 护栏 v127-PROMPT-BASELINE)。
 *
 * <p>一个「没标记 · 有房产 · 有保险 · 有房贷 · 没有其他类与贵金属 · 没写分析偏好」的家庭。
 * <b>这个类只用 v1.26.1 就有的构造器和方法</b> —— 同一份代码拷到 v1.26.1 tag 的工作区里跑一遍,
 * 生成 {@code src/test/resources/golden/v1261/*.txt};这一版用它比对。基线来自已发布 tag,
 * 分得清「代码改了」和「数据漂了」(memory:改指标口径前先建零差异基线)。</p>
 *
 * <p>数字是编的,不是任何真实家庭的(prod 数据只能看不能落文件)。</p>
 */
public final class PromptFixtures {

    private PromptFixtures() {}

    static BigDecimal d(String s) { return new BigDecimal(s); }

    public static FamilyDiagnose family() {
        KpiSnapshot kpi = new KpiSnapshot(d("5000000"), d("6200000"), d("1200000"), d("8.5"),
                d("0.193548"), d("35000"), d("0.007"));
        List<AllocationSlice> alloc = List.of(
                new AllocationSlice("PROPERTY", "房产\n(PROPERTY)", d("4000000.00"), d("0.645161")),
                new AllocationSlice("STOCK", "股票\n(STOCK)", d("900000.00"), d("0.145161")),
                new AllocationSlice("CASH", "现金\n(CASH)", d("600000.00"), d("0.096774")),
                new AllocationSlice("WEALTH", "理财\n(WEALTH)", d("400000.00"), d("0.064516")),
                new AllocationSlice("INSURANCE", "保险\n(INSURANCE)", d("300000.00"), d("0.048387")));
        List<FamilyDiagnose.RiskBucket> risk = List.of(
                new FamilyDiagnose.RiskBucket(1, "极低", d("600000.00"), d("0.096774")),
                new FamilyDiagnose.RiskBucket(2, "低", d("4700000.00"), d("0.758065")),
                new FamilyDiagnose.RiskBucket(4, "中", d("900000.00"), d("0.145161")));
        return new FamilyDiagnose(kpi, alloc, risk, d("600000.00"), d("8.5"), d("0.0412"), d("0.0388"),
                d("52000.00"), 6, 0, 0);
    }

    public static List<PromptBuilder.AccountSummary> accounts() {
        return List.of(
                new PromptBuilder.AccountSummary("招行工资卡", "CASH", "DEMAND_DEPOSIT", "★", "成员A", null, null, "活期", d("0.30")),
                new PromptBuilder.AccountSummary("券商主账户", "STOCK", "STOCK_CN", "★★★★", "成员B", null, null, "沪深300", d("8.00")),
                new PromptBuilder.AccountSummary("银行理财", "WEALTH", "WEALTH_R2", "★★", null, null, null, null, null),
                new PromptBuilder.AccountSummary("自住房", "PROPERTY", "PROPERTY_RES", "★★", "成员A", null, null, null, null),
                new PromptBuilder.AccountSummary("储蓄险", "INSURANCE", "SAVINGS_INSURANCE", "★★", "成员B", null, null, null, null),
                new PromptBuilder.AccountSummary("房贷", "LOAN", "LIABILITY", "—", "成员A", null, null, null, null));
    }

    public static List<Advice> advice() {
        return List.of(
                Advice.of("FAM-CON-1", Advice.Scope.FAMILY, null, Advice.Dimension.RISK_ALLOCATION, Advice.Severity.WARN,
                        "类目集中度偏高", "房产 (PROPERTY) 占总资产 65%,已过半。",
                        "考虑分散至其他类目(债券 / 海外股 / 货币基金),平滑组合波动。", "→ 看资产配置"));
    }

    public static Map<String, String> mapping() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("张三", "成员A");
        m.put("李四", "成员B");
        return m;
    }

    public static AccountDiagnose stockAccount() {
        Account a = new Account();
        a.setId(12L); a.setFamilyId(1L); a.setType(AccountType.STOCK);
        a.setCurrency("CNY"); a.setDisplayName("券商主账户");
        return new AccountDiagnose(a, null, d("900000"), d("880000"), d("20000"), 12,
                d("0"), d("0"), d("100000"), d("0"), d("100000"), d("60000"), d("0.072"), null,
                BenchmarkComparator.Result.noBenchmark(), 4, false, List.of());
    }

    public static AssetInsight insight() {
        BigDecimal th = d("40.0");
        var conc = new AssetInsight.Concentration(d("6200000"),
                ConcentrationCalculator.line(d("4000000"), d("6200000"), th), "自住房",
                ConcentrationCalculator.line(d("4000000"), d("6200000"), th), null,
                new ConcentrationCalculator.Line(null, th, false), th);
        var bs = BalanceSheetHealth.evaluate(d("2200000"), d("4000000"), d("1200000"), d("6200000"),
                d("3.950"), d("4.12"));
        // LinkedHashMap:遍历顺序固定(Map.of 每次启动随机;生产里的目标表是按枚举哈希的 HashMap,本来也不保证顺序 ——
        //   这里钉的是「格式」,顺序由材料决定)
        Map<String, BigDecimal> target = new LinkedHashMap<>();
        target.put("CASH", d("10.00")); target.put("INVEST", d("30.00"));
        target.put("PROPERTY", d("40.00")); target.put("INSURANCE", d("20.00"));
        Map<String, BigDecimal> current = new LinkedHashMap<>();
        current.put("CASH", d("9.68")); current.put("INVEST", d("20.97"));
        current.put("PROPERTY", d("64.52")); current.put("INSURANCE", d("4.84"));
        var drifts = RebalanceDrift.evaluate(target, current, d("10"));
        var lowRate = new AssetInsight.LowRate(d("9.68"), d("1.35"), d("-2.10"), d("3.40"));
        return new AssetInsight(conc, bs, d("3.950"), d("4.12"),
                new AssetInsight.Rebalance("SP_4321", d("10"), drifts), List.of(), lowRate, 12, true, null);
    }

    /** 基线文本:名字 → 提示词(生成器与比对共用同一张表) */
    public static Map<String, String> baselinePrompts() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("diagnose-system", PromptBuilder.systemPromptForDiagnose());
        out.put("diagnose-family-user", PromptBuilder.userPromptForFamily("我们家", family(), accounts(), advice(), mapping()));
        out.put("diagnose-account-user", PromptBuilder.userPromptForAccount("我们家", family(), stockAccount(),
                List.of(), mapping(), "成员B"));
        out.put("insight-system", InsightPromptBuilder.systemPrompt());
        out.put("insight-user", InsightPromptBuilder.userPrompt(insight()));
        return out;
    }
}
