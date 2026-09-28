package com.family.finance.service.analysis;

import com.family.finance.service.checkup.llm.PromptBuilder;
import com.family.finance.service.insight.InsightPromptBuilder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.27 · 护栏 v127-PROMPT-BASELINE(PRD FR-846 · §6 零差异基线 · 验收 #1 / #17)。
 *
 * <p>「综合体检 · 全部资产 · 没写分析偏好」,对一个有房产、有保险、有房贷的家庭,
 * 三个配置类 AI 的提示词与 <b>v1.26.1 tag</b> 逐字相同。金样本是把 {@link PromptFixtures} 原样拷到
 * v1.26.1 的工作区里跑出来的({@code src/test/resources/golden/v1261/}),不是这一版自己生成的 ——
 * 自己生成自己比,改坏了也是绿的。</p>
 *
 * <p>调仓建议不在这里:FR-875 要求它多带各账户余额(顺带修掉四桶数字一直是 null),本来就不该逐字相同。</p>
 */
class AnalysisPromptBaselineTest {

    private static String golden(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/golden/v1261/" + name + ".txt"), StandardCharsets.UTF_8);
    }

    @Test
    void legacyEntryPointsUnchangedSinceV1261() throws Exception {
        for (Map.Entry<String, String> e : PromptFixtures.baselinePrompts().entrySet()) {
            assertThat(e.getValue()).as("%s 与 v1.26.1 不一致", e.getKey()).isEqualTo(golden(e.getKey()));
        }
    }

    /** 这一版真正走的路径:基线上下文 → 段落为空 → 与 v1.26.1 逐字相同 */
    @Test
    void v127PathWithBaselineContextIsByteIdentical() throws Exception {
        var ctx = AnalysisContext.baseline();
        var family = PromptFixtures.family();
        String blocks = AnalysisPromptBlocks.forAnalysis(ctx, "「资产配置」「风险敞口」「各账户硬事实」", PromptFixtures.mapping());
        assertThat(blocks).isEmpty();

        assertThat(PromptBuilder.systemPromptForDiagnose(PromptBuilder.hasProperty(family)))
                .isEqualTo(golden("diagnose-system"));
        assertThat(PromptBuilder.userPromptForFamily("我们家", family, PromptFixtures.accounts(),
                PromptFixtures.advice(), PromptFixtures.mapping(), blocks))
                .isEqualTo(golden("diagnose-family-user"));
        assertThat(PromptBuilder.userPromptForAccount("我们家", family, PromptFixtures.stockAccount(), List.of(),
                PromptFixtures.mapping(), "成员B", AnalysisPromptBlocks.preferencesOnly(List.of(), PromptFixtures.mapping())))
                .isEqualTo(golden("diagnose-account-user"));

        var insight = PromptFixtures.insight();
        assertThat(InsightPromptBuilder.systemPrompt(insight.propertyInScope(), insight.hasLoans()))
                .isEqualTo(golden("insight-system"));
        assertThat(InsightPromptBuilder.userPrompt(insight,
                AnalysisPromptBlocks.forAnalysis(ctx, "「集中度」「再平衡偏离」「低利率·资产荒」", Map.of())))
                .isEqualTo(golden("insight-user"));
    }

    /** 反面:换了模板 / 写了偏好,提示词必须变(否则等于没接上) */
    @Test
    void templateOrPreferenceChangesThePrompt() throws Exception {
        var steady = BuiltinTemplates.find(BuiltinTemplates.STEADY).orElseThrow();
        var ctx = new AnalysisContext(AnalysisScope.all(), steady, List.of("房子是自住的,张三说别建议卖"), "平衡", null);
        String blocks = AnalysisPromptBlocks.forAnalysis(ctx, "「资产配置」", PromptFixtures.mapping());
        String user = PromptBuilder.userPromptForFamily("我们家", PromptFixtures.family(), PromptFixtures.accounts(),
                PromptFixtures.advice(), PromptFixtures.mapping(), blocks);
        assertThat(user).isNotEqualTo(golden("diagnose-family-user"))
                .contains("## 分析模板:稳健守护")
                .contains("## 家里人交代的分析偏好(1 条)")
                .contains("成员A说别建议卖")          // 真名在偏好里也被替换(PRD §8)
                .doesNotContain("张三");
        // 段落在全部材料之后、最后那句要求之前(PRD §9 ⑤ ⑦ · v127-BLOCK-ORDER)
        assertThat(user.indexOf("## 6. 成员代号映射")).isLessThan(user.indexOf("## 分析模板"));
        assertThat(user.indexOf("## 家里人交代的分析偏好")).isLessThan(user.lastIndexOf("---\n请输出"));
    }
}
