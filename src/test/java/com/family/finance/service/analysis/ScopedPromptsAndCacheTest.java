package com.family.finance.service.analysis;

import com.family.finance.domain.family.Family;
import com.family.finance.service.allocation.AllocationService;
import com.family.finance.service.allocation.RebalanceAdvisorService;
import com.family.finance.service.checkup.FamilyDiagnose;
import com.family.finance.service.checkup.llm.PromptBuilder;
import com.family.finance.service.insight.AssetInsight;
import com.family.finance.service.insight.InsightPromptBuilder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.27 · 按家里实际有的说话(FR-872)· 缓存按范围 / 模板 / 偏好分开(FR-848 · 验收 #7)· 配置锚放大说明(FR-870)。
 */
class ScopedPromptsAndCacheTest {

    static AssetInsight insightWith(AnalysisScope scope, boolean property, boolean loans) {
        AssetInsight b = PromptFixtures.insight();
        var conc = property ? b.concentration()
                : new AssetInsight.Concentration(b.concentration().totalAssets(), null, "券商",
                        b.concentration().topAccount(), null, b.concentration().topCurrency(), b.concentration().thresholdPct());
        return new AssetInsight(conc, b.balanceSheet(), b.weightedLoanRatePct(), b.assetAnnualReturnPct(), b.rebalance(),
                b.behaviorSignals(), b.lowRate(), b.historyPeriods(), true, null, scope, property, loans);
    }

    /** FR-872 · 范围内没有房产:不谈「房产占比」「金融盘 vs 不动产」;没有贷款:不谈提前还贷 */
    @Test
    void insightSpeaksOnlyAboutWhatTheFamilyHas() {
        String sys = InsightPromptBuilder.systemPrompt(false, false);
        assertThat(sys).doesNotContain("房产/单一账户").doesNotContain("金融盘 vs 不动产").doesNotContain("提前还贷信号)")
                .doesNotContain("加速偿还/再平衡").contains("家里没有贷款");
        String user = InsightPromptBuilder.userPrompt(insightWith(AnalysisScope.all(), false, false), "");
        assertThat(user).doesNotContain("房产占比").doesNotContain("金融盘占").doesNotContain("提前还贷信号")
                .contains("- 贷款: 无 · 不涉及提前还贷");
    }

    @Test
    void scopedInsightNamesItsDenominatorAndCarriesBlocks() {
        var fin = new AnalysisScope(ScopeKind.FINANCIAL, Set.of(1L), List.of("自住房"), List.of("房产类"),
                new BigDecimal("0.645"), false);
        var ctx = new AnalysisContext(fin, BuiltinTemplates.find("PORTFOLIO").orElseThrow(), List.of(), "平衡", null);
        String user = InsightPromptBuilder.userPrompt(insightWith(fin, false, true),
                AnalysisPromptBlocks.forAnalysis(ctx, "「集中度」", Map.of()));
        assertThat(user).contains("## 1. 集中度(占金融资产)").contains("- 金融资产合计:")
                .contains("## 分析范围:金融资产").contains("## 分析模板:投资组合体检");
        assertThat(user.indexOf("## 分析范围")).isGreaterThan(user.indexOf("## 4. 低利率"));
    }

    /** FR-872 · 诊断:范围内没有房产 → 系统提示词不说「4 桶」 */
    @Test
    void diagnoseSystemPromptWithoutProperty() {
        assertThat(PromptBuilder.systemPromptForDiagnose(false))
                .doesNotContain("现金/投资/房产/保险 4 桶").contains("现金、投资、保险等各类资产之间的比例");
        FamilyDiagnose f = PromptFixtures.family();
        assertThat(PromptBuilder.hasProperty(f)).isTrue();
    }

    /** 验收 #7 · 调仓缓存键:基线 = 纯锚码(老缓存照常命中);范围 / 模板 / 偏好 / 自定义锚任一变了都不同 */
    @Test
    void rebalanceCacheKeySeparatesContexts() {
        var svc = new RebalanceAdvisorService(null, null, null, null, null, null, null, null);
        Family f = new Family();
        f.setAllocationAnchor("SP_4321");
        String base = svc.cacheKey(f, AnalysisContext.baseline());
        assertThat(base).isEqualTo("SP_4321");
        var adj = new AnalysisScope(ScopeKind.ADJUSTABLE, Set.of(1L), List.of("自住房"), List.of(), BigDecimal.ONE, false);
        String scoped = svc.cacheKey(f, new AnalysisContext(adj, BuiltinTemplates.general(), List.of(), null, null));
        String tpl = svc.cacheKey(f, new AnalysisContext(AnalysisScope.all(), BuiltinTemplates.find("STEADY").orElseThrow(), List.of(), null, null));
        String pref = svc.cacheKey(f, new AnalysisContext(AnalysisScope.all(), BuiltinTemplates.general(), List.of("别卖房"), null, null));
        assertThat(Set.of(base, scoped, tpl, pref)).hasSize(4);
        assertThat(scoped).startsWith("SP_4321|").hasSizeLessThanOrEqualTo(32);
        f.setAllocationAnchor("CUSTOM");
        f.setAllocationAnchorCustom("{\"cash\":20,\"invest\":80,\"property\":0,\"insurance\":0}");
        String c1 = svc.cacheKey(f, AnalysisContext.baseline());
        f.setAllocationAnchorCustom("{\"cash\":30,\"invest\":70,\"property\":0,\"insurance\":0}");
        assertThat(svc.cacheKey(f, AnalysisContext.baseline())).isNotEqualTo(c1);
        f.setAllocationAnchor("XQ_CONSERVATIVE");
        assertThat(svc.cacheKey(f, new AnalysisContext(adj, BuiltinTemplates.general(), List.of(), null, null)))
                .hasSizeLessThanOrEqualTo(32);   // 最长的锚码 + 指纹也放得进 VARCHAR(32)
    }

    /** FR-870 关键文案 */
    @Test
    void rescaleNote() {
        var diff = new AllocationService.DiffResult("SP_4321",
                Map.of("CASH", new BigDecimal("16.67"), "INVEST", new BigDecimal("50.00"), "INSURANCE", new BigDecimal("33.33")),
                Map.of("CASH", BigDecimal.TEN, "INVEST", BigDecimal.TEN, "INSURANCE", BigDecimal.TEN),
                Map.of("CASH", BigDecimal.ZERO, "INVEST", BigDecimal.ZERO, "INSURANCE", BigDecimal.ZERO),
                Map.of(), List.of("PROPERTY"), true, false, BigDecimal.ZERO, false, false, AnalysisScope.all());
        assertThat(diff.rescaleNote("标普 4321")).isEqualTo("你家没有房产 · 标普 4321 按其余三类放大 → 现金 17% · 投资 50% · 保险 33%");
        assertThat(diff.activeBuckets()).containsExactly("CASH", "INVEST", "INSURANCE");
    }
}
