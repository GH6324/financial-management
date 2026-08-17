package com.family.finance.web.admin;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.member.Member;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.checkup.llm.LlmRouter;
import com.family.finance.service.config.FamilyConfigService;
import com.family.finance.service.macro.MacroBenchmarkService;
import com.family.finance.service.scheduling.DynamicScheduleConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * v1.13 FR-360 · 型号手填的<b>校验边界</b>:合法的原样收下,不合法的<b>当面报错</b>。
 *
 * <p>这条测试针对的是 v0.14 留下的一个具体坏形态:那时候型号是「取值白名单 + 越权静默
 * 回落 auto」。方舟的接入点 ID({@code ep-xxxx})不以任何已知前缀开头,必然越权 ——
 * 于是用户填了、页面显示着、库里存的却是空,实际调用走的是别的型号。<b>静默回落比报错
 * 危险得多</b>:报错用户会改,回落用户查不出来。</p>
 *
 * <p>所以断言分两半:合法型号必须<b>原样落库</b>(不被规整、不被截断),不合法必须
 * <b>整单退回、一个字都不写</b>(§ saveLlm「校验先全跑完再落库」)。</p>
 */
class LlmModelFormatTest {

    private final FamilyConfigService config = mock(FamilyConfigService.class);
    private final AuditLogService audit = mock(AuditLogService.class);

    private IntegrationsController controller() {
        return new IntegrationsController(config, mock(DynamicScheduleConfig.class), audit,
                mock(MacroBenchmarkService.class), mock(LlmRouter.class), new ObjectMapper(), List.of());
    }

    private static MemberPrincipal me() {
        Member m = new Member();
        m.setId(9L);
        m.setFamilyId(1L);
        m.setUsername("tester");
        return new MemberPrincipal(m);
    }

    /** 只填主选(视觉关掉、备选留空)· 返回 flash 容器供断言 */
    private RedirectAttributes saveWithPrimaryModel(String platform, String family, String modelId) {
        RedirectAttributes ra = new RedirectAttributesModelMap();
        controller().saveLlm(me(), null, null, null,
                platform, family, modelId,
                null, null, null,
                false, "dashscope", "qwen-vl", "",
                0.5, 2000, 25, ra);
        return ra;
    }

    private static String flashError(RedirectAttributes ra) {
        Object v = ra.getFlashAttributes().get("flashError");
        return v == null ? null : v.toString();
    }

    // ---------------- 合法:原样收下 ----------------

    @Test
    void arkEndpointId_isAccepted_andStoredVerbatim() {
        // 方舟控制台生成的接入点 ID · v0.14 的白名单必然把它判越权
        RedirectAttributes ra = saveWithPrimaryModel("ark", "doubao", "ep-20260815120000-abcde");

        assertThat(flashError(ra)).as("接入点 ID 被拒了 —— 方舟就没法用了").isNull();
        verify(config).set(1L, FamilyConfigService.K_LLM_MODEL_ID, "ep-20260815120000-abcde");
        verify(config).set(1L, FamilyConfigService.K_LLM_PLATFORM, "ark");
        verify(config).set(1L, FamilyConfigService.K_LLM_FAMILY, "doubao");
    }

    @Test
    void arkDatedModelId_isAccepted() {
        // 带日期版本的模型 ID · 同样只能从控制台抄
        assertThat(flashError(saveWithPrimaryModel("ark", "deepseek", "doubao-seed-1-6-251015"))).isNull();
        verify(config).set(1L, FamilyConfigService.K_LLM_MODEL_ID, "doubao-seed-1-6-251015");
    }

    @Test
    void recommendedModel_isAccepted() {
        assertThat(flashError(saveWithPrimaryModel("deepseek", "deepseek", "deepseek-chat"))).isNull();
        verify(config).set(1L, FamilyConfigService.K_LLM_MODEL_ID, "deepseek-chat");
    }

    @Test
    void blankModel_meansAuto_onAFamilyThatHasADefault() {
        // 留空 = 自动(系列默认型号)· 这是合法的,不是「没填」
        assertThat(flashError(saveWithPrimaryModel("dashscope", "qwen", "  "))).isNull();
        verify(config).set(1L, FamilyConfigService.K_LLM_MODEL_ID, "");
    }

    // ---------------- 不合法:报错,且一个字都不写 ----------------

    @Test
    void modelWithSpace_isRejected_notSilentlyFalledBack() {
        RedirectAttributes ra = saveWithPrimaryModel("ark", "doubao", "ep-2026 abcde");

        assertThat(flashError(ra)).contains("型号格式不合法");
        verify(config, never()).set(anyLong(), anyString(), anyString());
        verify(audit, never()).record(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any(),
                anyString(), anyLong(), anyString());
    }

    @Test
    void modelWithQuote_isRejected() {
        assertThat(flashError(saveWithPrimaryModel("ark", "doubao", "ep\"; drop"))).contains("型号格式不合法");
        verify(config, never()).set(anyLong(), anyString(), anyString());
    }

    @Test
    void modelLongerThan64Chars_isRejected() {
        assertThat(flashError(saveWithPrimaryModel("ark", "doubao", "e".repeat(65)))).contains("型号格式不合法");
        verify(config, never()).set(anyLong(), anyString(), anyString());
    }

    /**
     * 报错文案<b>不回显用户填的原串</b>:这个输入框最常见的误填就是把 API key 粘进来,
     * 回显 = 把 key 写进 flash(可能被日志/截图带走)。只说格式要求。
     */
    @Test
    void rejectMessage_doesNotEchoWhatUserTyped() {
        String pastedKey = "sk-abcdef0123456789 THIS_IS_A_KEY";
        String msg = flashError(saveWithPrimaryModel("ark", "doubao", pastedKey));

        assertThat(msg).contains("型号格式不合法").doesNotContain("sk-abcdef", "THIS_IS_A_KEY");
    }

    // ---------------- 必须手填的系列:不许静默用默认 ----------------

    @Test
    void arkFamilyWithoutRecommendedModels_requiresExplicitModel() {
        // 方舟三个系列都没有可预置的型号 · 留空不能变成「自动」,那样会调一个不存在的型号
        String msg = flashError(saveWithPrimaryModel("ark", "doubao", ""));

        assertThat(msg).contains("必须手工填写型号");
        verify(config, never()).set(anyLong(), anyString(), anyString());
    }

    @Test
    void visionOff_allowsHalfConfiguredVision_butStillRejectsBadFormat() {
        // 关掉截图识别时不强求填型号(配置留着下次开)· 但填了就得合法
        RedirectAttributes ok = new RedirectAttributesModelMap();
        controller().saveLlm(me(), null, null, null,
                "dashscope", "qwen", "", null, null, null,
                false, "ark", "doubao-vision", "", 0.5, 2000, 25, ok);
        assertThat(flashError(ok)).as("视觉关着还要求填型号 · 用户被卡在一个他不用的字段上").isNull();
        verify(config).set(1L, FamilyConfigService.K_LLM_VISION_ENABLED, "false");

        RedirectAttributes bad = new RedirectAttributesModelMap();
        controller().saveLlm(me(), null, null, null,
                "dashscope", "qwen", "", null, null, null,
                false, "ark", "doubao-vision", "ep 有空格", 0.5, 2000, 25, bad);
        assertThat(flashError(bad)).contains("型号格式不合法");
    }

    // ---------------- 平台/系列本身的错配 ----------------

    @Test
    void unknownPlatform_isRejected() {
        assertThat(flashError(saveWithPrimaryModel("openai", "gpt", "gpt-4o"))).contains("请选择平台");
        verify(config, never()).set(anyLong(), anyString(), anyString());
    }

    @Test
    void familyFromAnotherPlatform_isRejected() {
        // 「qwen」是百炼的系列,挂到方舟下面不成立 —— 级联下拉坏掉时会出现这种提交
        assertThat(flashError(saveWithPrimaryModel("ark", "qwen", "ep-1"))).contains("不是");
        verify(config, never()).set(anyLong(), anyString(), anyString());
    }

    @Test
    void visionFamilyMustBeAVisionFamily() {
        RedirectAttributes ra = new RedirectAttributesModelMap();
        controller().saveLlm(me(), null, null, null,
                "dashscope", "qwen", "", null, null, null,
                true, "dashscope", "qwen", "", 0.5, 2000, 25, ra);   // 文本系列塞进视觉位
        assertThat(flashError(ra)).contains("视觉模型系列");
        verify(config, never()).set(anyLong(), anyString(), anyString());
    }

    @Test
    void backupIdenticalToPrimary_isRejected() {
        RedirectAttributes ra = new RedirectAttributesModelMap();
        controller().saveLlm(me(), null, null, null,
                "dashscope", "qwen", "qwen-plus",
                "dashscope", "qwen", "qwen-plus",
                false, "dashscope", "qwen-vl", "", 0.5, 2000, 25, ra);

        assertThat(flashError(ra)).contains("等于没有备选");
        verify(config, never()).set(anyLong(), anyString(), anyString());
    }

    // ---------------- key 的私密红线 ----------------

    @Test
    void blankKeys_keepExistingValues_andKeysNeverEnterFlashOrAudit() {
        RedirectAttributes ra = new RedirectAttributesModelMap();
        controller().saveLlm(me(), "  ", null, "ark-secret-key",
                "ark", "doubao", "ep-1", null, null, null,
                false, "dashscope", "qwen-vl", "", 0.5, 2000, 25, ra);

        verify(config, never()).set(anyLong(), eq(FamilyConfigService.K_LLM_QWEN_KEY), anyString());
        verify(config, never()).set(anyLong(), eq(FamilyConfigService.K_LLM_DEEPSEEK_KEY), anyString());
        verify(config, times(1)).set(1L, FamilyConfigService.K_LLM_ARK_KEY, "ark-secret-key");

        assertThat(ra.getFlashAttributes().toString()).doesNotContain("ark-secret-key");
        verify(audit).record(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any(),
                anyString(), anyLong(),
                org.mockito.ArgumentMatchers.argThat(d -> !d.contains("ark-secret-key")));
    }
}
