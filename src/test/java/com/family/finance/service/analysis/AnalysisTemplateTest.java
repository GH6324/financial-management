package com.family.finance.service.analysis;

import com.family.finance.repository.AnalysisTemplateMapper;
import com.family.finance.service.analysis.AnalysisTemplate.Focus;
import com.family.finance.service.analysis.AnalysisTemplate.Stance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.27 · 分析模板(PRD §3.3 · FR-841 ~ FR-844 · 验收 #17 / #19)。
 */
class AnalysisTemplateTest {

    @Test
    void fiveBuiltinsWithDistinctKeysAndGeneralIsBaseline() {
        assertThat(BuiltinTemplates.ALL).extracting(AnalysisTemplate::key)
                .containsExactly("GENERAL", "STEADY", "GROWTH", "PORTFOLIO", "DEBT");
        assertThat(BuiltinTemplates.ALL).extracting(AnalysisTemplate::name)
                .containsExactly("综合体检", "稳健守护", "积极增长", "投资组合体检", "负债与现金流");
        assertThat(BuiltinTemplates.general().isBaseline()).isTrue();
        assertThat(BuiltinTemplates.ALL.stream().filter(AnalysisTemplate::isBaseline)).hasSize(1);
        for (AnalysisTemplate t : BuiltinTemplates.ALL) {
            assertThat(t.focus()).as(t.key()).hasSizeBetween(1, 4);
            assertThat(t.suitsWho()).as(t.key()).isNotBlank();
        }
        assertThat(BuiltinTemplates.find("PORTFOLIO").orElseThrow().scope()).isEqualTo(ScopeKind.FINANCIAL);
    }

    /** 验收 #17 · 各内置模板的结构化设置真的进了提示词 */
    @Test
    void builtinSettingsReachThePrompt() {
        assertThat(AnalysisPromptBlocks.template(BuiltinTemplates.general(), null, "平衡")).isEmpty();
        String steady = AnalysisPromptBlocks.template(BuiltinTemplates.find("STEADY").orElseThrow(), null, "平衡");
        assertThat(steady).contains("## 分析模板:稳健守护").contains("立场:稳健").contains("侧重:风险、流动性、集中度");
        String growth = AnalysisPromptBlocks.template(BuiltinTemplates.find("GROWTH").orElseThrow(), null, "平衡");
        assertThat(growth).contains("立场:进取").contains("不荐股");
        String debt = AnalysisPromptBlocks.template(BuiltinTemplates.find("DEBT").orElseThrow(), null, "保守");
        assertThat(debt).contains("跟随家里的风险偏好(保守)").contains("负债与现金流");
    }

    /** FR-843 · 「基于它定制」存完整副本,来源 key 与版本只作记录 */
    @Test
    void customizeStoresFullCopy() {
        AnalysisTemplateMapper mapper = mock(AnalysisTemplateMapper.class);
        when(mapper.countByFamily(anyLong())).thenReturn(0);
        final AnalysisTemplateMapper.Row[] saved = new AnalysisTemplateMapper.Row[1];
        when(mapper.insert(any())).thenAnswer(inv -> {
            saved[0] = inv.getArgument(0);
            saved[0].id = 7L; saved[0].version = 1;
            return 1;
        });
        when(mapper.findById(1L, 7L)).thenAnswer(inv -> Optional.of(saved[0]));
        var svc = new AnalysisTemplateService(mapper, null);
        var steady = BuiltinTemplates.find("STEADY").orElseThrow();
        var draft = AnalysisTemplateService.draftFrom(steady);
        assertThat(draft.name()).isEqualTo("我家的稳健守护");

        var t = svc.customize(1L, 3L, steady, new AnalysisTemplateService.Draft("我家的稳健守护",
                List.of(Focus.RISK, Focus.LIQUIDITY), Stance.CONSERVATIVE, null, null, "重点看港股仓位"));
        assertThat(saved[0].focus).isEqualTo("RISK,LIQUIDITY");
        assertThat(saved[0].stance).isEqualTo("CONSERVATIVE");
        assertThat(saved[0].sourceKey).isEqualTo("STEADY");
        assertThat(saved[0].sourceVersion).isEqualTo(steady.version());
        assertThat(t.key()).isEqualTo("custom:7");
        assertThat(t.sourceName()).isEqualTo("稳健守护");
        assertThat(t.extra()).isEqualTo("重点看港股仓位");
    }

    /** 验收 #19 · 内置模板升级后「我的模板」逐字不变 —— 副本只从自己的行里读,不回头看内置的设置 */
    @Test
    void customCopyIsIndependentOfBuiltinUpgrade() {
        AnalysisTemplateMapper.Row row = new AnalysisTemplateMapper.Row();
        row.id = 9L; row.familyId = 1L; row.name = "我家的稳健守护"; row.sourceKey = "STEADY"; row.sourceVersion = 1;
        row.focus = "RISK,LIQUIDITY,CONCENTRATION"; row.stance = "CONSERVATIVE"; row.version = 3;
        AnalysisTemplate copy = AnalysisTemplateService.fromRow(row);
        String before = AnalysisPromptBlocks.template(copy, copy.sourceName(), "平衡");
        // 就算内置「稳健守护」明天把侧重改成别的,这份副本的提示词段落只取决于它自己那一行
        var builtin = BuiltinTemplates.find("STEADY").orElseThrow();
        assertThat(copy.focus()).isEqualTo(List.of(Focus.RISK, Focus.LIQUIDITY, Focus.CONCENTRATION));
        assertThat(copy.version()).isNotEqualTo(builtin.version());
        assertThat(AnalysisPromptBlocks.template(AnalysisTemplateService.fromRow(row), copy.sourceName(), "平衡"))
                .isEqualTo(before)
                .contains("## 分析模板:我家的稳健守护(基于「稳健守护」)");
        assertThat(copy.versionedKey()).isEqualTo("custom:9@3");
    }

    @Test
    void draftValidation() {
        assertThatThrownBy(() -> new AnalysisTemplateService.Draft(" ", List.of(Focus.RISK), null, null, null, null).validated())
                .hasMessageContaining("名字");
        assertThatThrownBy(() -> new AnalysisTemplateService.Draft("x", List.of(), null, null, null, null).validated())
                .hasMessageContaining("1 到 4");
        assertThatThrownBy(() -> new AnalysisTemplateService.Draft("x",
                List.of(Focus.RISK, Focus.RETURN, Focus.DEBT, Focus.LIQUIDITY, Focus.ALLOCATION), null, null, null, null).validated())
                .hasMessageContaining("1 到 4");
        assertThatThrownBy(() -> new AnalysisTemplateService.Draft("x", List.of(Focus.RISK), null, null, null,
                "字".repeat(501)).validated()).hasMessageContaining("500");
        assertThatThrownBy(() -> new AnalysisTemplateService.Draft("x", List.of(Focus.RISK), null, null, "NOPE", null).validated())
                .hasMessageContaining("配置锚");
        var ok = new AnalysisTemplateService.Draft(" 我的 ", List.of(Focus.RISK, Focus.RISK), null, null, "sp_4321", "  ").validated();
        assertThat(ok.name()).isEqualTo("我的");
        assertThat(ok.focus()).containsExactly(Focus.RISK);
        assertThat(ok.stance()).isEqualTo(Stance.FOLLOW);
        assertThat(ok.anchor()).isEqualTo("SP_4321");
        assertThat(ok.extra()).isNull();
    }

    @Test
    void customLimitIsTen() {
        AnalysisTemplateMapper mapper = mock(AnalysisTemplateMapper.class);
        when(mapper.countByFamily(anyLong())).thenReturn(AnalysisTemplateService.MAX_CUSTOM);
        var svc = new AnalysisTemplateService(mapper, null);
        assertThatThrownBy(() -> svc.customize(1L, 1L, BuiltinTemplates.general(),
                AnalysisTemplateService.draftFrom(BuiltinTemplates.general())))
                .hasMessageContaining("最多 10 个");
    }

    @Test
    void focusCsvRoundTripDropsUnknownAndDuplicates() {
        assertThat(Focus.parseCsv("RISK,NOPE,RISK,debt")).containsExactly(Focus.RISK, Focus.DEBT);
        assertThat(Focus.toCsv(List.of(Focus.RETURN, Focus.ALLOCATION))).isEqualTo("RETURN,ALLOCATION");
        assertThat(Stance.parse("nope")).isEqualTo(Stance.FOLLOW);
        assertThat(Set.copyOf(List.of(Stance.values()))).hasSize(4);
    }
}
