package com.family.finance.service.checkup.rule;

import com.family.finance.calc.BenchmarkComparator;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.factview.AllocationSlice;
import com.family.finance.factview.KpiSnapshot;
import com.family.finance.service.analysis.AnalysisScope;
import com.family.finance.service.analysis.ScopeKind;
import com.family.finance.service.checkup.AccountDiagnose;
import com.family.finance.service.checkup.FamilyDiagnose;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.27 · 规则跟随分析范围 + 顺带的纠错(PRD FR-804 / FR-805 / FR-824 / FR-826 · §13 ⑩)。
 */
class ScopedRulesTest {

    static final BigDecimal Z = BigDecimal.ZERO;

    static KpiSnapshot kpi(String total) {
        return new KpiSnapshot(new BigDecimal(total), new BigDecimal(total), Z, null, null, null, null);
    }

    static AllocationSlice slice(String type, String label, String value, String ratio) {
        return new AllocationSlice(type, label, new BigDecimal(value), new BigDecimal(ratio));
    }

    static FamilyDiagnose family(List<AllocationSlice> alloc, AnalysisScope scope) {
        return new FamilyDiagnose(kpi("11200000"), alloc, List.of(), null, null, null, null, null, 5, 0, 0,
                scope, List.of());
    }

    static Account acc(long id, AccountType t, String name, boolean marked) {
        Account a = new Account();
        a.setId(id); a.setFamilyId(1L); a.setType(t); a.setDisplayName(name); a.setCurrency("CNY");
        a.setAnalysisExcluded(marked);
        return a;
    }

    static AccountDiagnose diag(Account a, String orig, String base) {
        return new AccountDiagnose(a, null, new BigDecimal(orig), null, null, 12, Z, Z, Z, Z, Z, Z, null, null,
                BenchmarkComparator.Result.noBenchmark(), 5, false, List.of(), base == null ? null : new BigDecimal(base));
    }

    static AnalysisScope adjustable() {
        return new AnalysisScope(ScopeKind.ADJUSTABLE, Set.of(1L, 2L), List.of("自住房", "家用车"), List.of(),
                new BigDecimal("0.91"), false);
    }

    /** FR-804 · 过线的是房产:行动按钮直达那套房的勾选项 */
    @Test
    void famCon1OnPropertyLinksToMarkCheckbox() {
        var f = family(List.of(slice("PROPERTY", "房产\n(PROPERTY)", "10000000", "0.89")), AnalysisScope.all());
        var ctx = RuleContext.forFamily(f, List.of(diag(acc(1, AccountType.PROPERTY, "自住房", false), "10000000", null)), null);
        var a = new FamilyRules.FamCon1TypeOverweight().evaluate(ctx).orElseThrow();
        assertThat(a.cta()).isEqualTo("标成不参与配置分析 →");
        assertThat(a.ctaHref()).isEqualTo("/accounts/1/edit#analysis-excluded");
        assertThat(a.rawTitle()).isEqualTo("房产 (PROPERTY) 占总资产 89%,已过半。");   // 全部资产:与 v1.26 逐字相同
    }

    /** 同类有好几个账户 → 账户列表按类筛出来 */
    @Test
    void famCon1WithSeveralPropertiesGoesToFilteredList() {
        var f = family(List.of(slice("PROPERTY", "房产", "10", "0.9")), AnalysisScope.all());
        var ctx = RuleContext.forFamily(f, List.of(diag(acc(1, AccountType.PROPERTY, "A", false), "5", null),
                diag(acc(7, AccountType.PROPERTY, "B", false), "5", null)), null);
        assertThat(new FamilyRules.FamCon1TypeOverweight().evaluate(ctx).orElseThrow().ctaHref())
                .isEqualTo("/accounts?type=PROPERTY&mark=analysis");
    }

    /** 过线的是股票:按钮不变;可调部分的文案带「不含」(PRD §9 ⑧) */
    @Test
    void famCon1OnStockKeepsOldCtaAndSaysWhatIsExcluded() {
        var f = family(List.of(slice("STOCK", "股票", "600000", "0.60")), adjustable());
        var a = new FamilyRules.FamCon1TypeOverweight().evaluate(RuleContext.forFamily(f, List.of(), null)).orElseThrow();
        assertThat(a.cta()).isEqualTo("→ 看资产配置");
        assertThat(a.ctaHref()).isNull();
        assertThat(a.rawTitle()).isEqualTo("股票 占可调整的资产 60%,已过半。(不含 自住房、家用车)");
    }

    /** FR-826 · 范围为空:配置类规则一条都不亮 */
    @Test
    void emptyScopeSilencesAllocationRules() {
        var empty = new AnalysisScope(ScopeKind.ADJUSTABLE, Set.of(3L), List.of("工资卡"), List.of(), BigDecimal.ONE, true);
        var f = family(List.of(), empty);
        var ctx = RuleContext.forFamily(f, List.of(), null);
        assertThat(new FamilyRules.FamCon1TypeOverweight().evaluate(ctx)).isEmpty();
        assertThat(new FamilyRules.FamCon2SingleType().evaluate(ctx)).isEmpty();
        assertThat(new FamilyRules.FamRisk2AllConservative().evaluate(ctx)).isEmpty();
    }

    /** §13 ⑩ · 只持有贵金属 / 加密也算有投资 */
    @Test
    void famRisk2CountsMetalAndCryptoAsInvestment() {
        var f = family(List.of(), AnalysisScope.all());
        var metalOnly = List.of(diag(acc(3, AccountType.CASH, "卡", false), "200000", null),
                diag(acc(4, AccountType.METAL, "积存金", false), "50000", null));
        assertThat(new FamilyRules.FamRisk2AllConservative().evaluate(RuleContext.forFamily(f, metalOnly, null))).isEmpty();
        var cashOnly = List.of(diag(acc(3, AccountType.CASH, "卡", false), "200000", null));
        assertThat(new FamilyRules.FamRisk2AllConservative().evaluate(RuleContext.forFamily(f, cashOnly, null))).isPresent();
    }

    /** FR-805 · 被标记的账户,账户级集中度与风险规则不再触发 */
    @Test
    void markedAccountSkipsConcentrationRules() {
        var fam = family(List.of(), AnalysisScope.all());
        var house = diag(acc(1, AccountType.PROPERTY, "自住房", true), "10000000", "10000000");
        assertThat(new AccountRules.Risk1SingleAccountOverlimit().evaluate(RuleContext.forAccount(house, fam, List.of(house), null))).isEmpty();
        var unmarked = diag(acc(1, AccountType.PROPERTY, "自住房", false), "10000000", "10000000");
        assertThat(new AccountRules.Risk1SingleAccountOverlimit().evaluate(RuleContext.forAccount(unmarked, fam, List.of(unmarked), null))).isPresent();
    }

    /** §13 ⑩ · 外币账户:占比用本位币余额(原来原币 ÷ 本位币总资产) */
    @Test
    void accountShareUsesBaseCurrency() {
        var fam = family(List.of(), AnalysisScope.all());   // 总资产 1120 万
        // 50 万美元 ≈ 360 万人民币 → 32% 应该亮;按原币 50 万算只有 4%,原来不亮
        var usd = diag(acc(9, AccountType.STOCK, "美股", false), "500000", "3600000");
        assertThat(new AccountRules.Risk1SingleAccountOverlimit().evaluate(RuleContext.forAccount(usd, fam, List.of(usd), null))).isPresent();
    }
}
