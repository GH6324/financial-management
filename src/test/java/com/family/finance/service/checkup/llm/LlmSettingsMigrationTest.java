package com.family.finance.service.checkup.llm;

import com.family.finance.service.config.FamilyConfigService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v1.13 FR-363 · <b>读时派生</b>护栏:老家庭升级后不写库,直接从旧键推出等价三元组。
 *
 * <p>这条链路没有迁移 SQL,所以也没有「迁移跑过了」这个可观测事件 —— 唯一能证明它对的
 * 就是这个测试。三件事必须成立:派生结果与 v1.12 的实际行为等价、纯读不写(否则回滚时
 * 旧代码读到被改过的键)、新键一旦存在旧键立刻失效(否则用户在管理页改了没反应)。</p>
 */
class LlmSettingsMigrationTest {

    private final Map<String, String> store = new HashMap<>();
    private FamilyConfigService cfg;

    private FamilyConfigService config() {
        cfg = mock(FamilyConfigService.class);
        when(cfg.getString(anyLong(), anyString(), any())).thenAnswer(i -> {
            String key = i.getArgument(1);
            return store.containsKey(key) ? store.get(key) : i.getArgument(2);
        });
        when(cfg.getBoolean(anyLong(), anyString(), anyBoolean())).thenAnswer(i -> {
            String key = i.getArgument(1);
            return store.containsKey(key) ? Boolean.parseBoolean(store.get(key)) : i.getArgument(2);
        });
        return cfg;
    }

    // ---------------- 旧配置派生 ----------------

    @Test
    void v112Config_derivesEquivalentTriples_andNeverWrites() {
        // 一份 v1.12 真实形态:两把 key 都配了、主选 deepseek、型号钉死、视觉用非默认型号
        store.put(FamilyConfigService.K_LLM_PRIMARY_VENDOR, "deepseek");
        store.put(FamilyConfigService.K_LLM_MODEL, "deepseek-reasoner");
        store.put(FamilyConfigService.K_LLM_VISION_MODEL, "qwen-vl-plus");

        LlmSettings s = LlmSettings.load(config(), 1L);

        assertThat(s.legacy()).isTrue();
        // 主选:DeepSeek 官方 · 型号原样承接(它确实属于这一家)
        assertThat(s.primary().platform()).isEqualTo(LlmCatalog.P_DEEPSEEK);
        assertThat(s.primary().family()).isEqualTo("deepseek");
        assertThat(s.primary().model()).isEqualTo("deepseek-reasoner");
        // 备选:另一家自动补位(等价于 v0.14 orderByPrimaryVendor 的两家排序)
        assertThat(s.backup()).isPresent();
        assertThat(s.backup().get().platform()).isEqualTo(LlmCatalog.P_DASHSCOPE);
        assertThat(s.backup().get().family()).isEqualTo("qwen");
        // 视觉:百炼 · 通义千问 VL · 型号原样
        assertThat(s.visionEnabled()).isTrue();
        assertThat(s.vision().platform()).isEqualTo(LlmCatalog.P_DASHSCOPE);
        assertThat(s.vision().family()).isEqualTo("qwen-vl");
        assertThat(s.vision().resolvedModel()).isEqualTo("qwen-vl-plus");

        verify(cfg, never()).set(anyLong(), anyString(), any());   // 读时派生 = 一个字都不写
    }

    @Test
    void v112Config_isPureFunction_twoReadsAreIdentical() {
        store.put(FamilyConfigService.K_LLM_PRIMARY_VENDOR, "qwen");
        store.put(FamilyConfigService.K_LLM_MODEL, "qwen-max");
        FamilyConfigService c = config();

        assertThat(LlmSettings.load(c, 1L)).isEqualTo(LlmSettings.load(c, 1L));
    }

    @Test
    void legacyModelBelongingToTheOtherVendor_isNotCarriedOver() {
        // v1.12 的 pinnedModel() 按前缀判断,不是自家的就退回自动。这里必须给出同样结果 ——
        // 否则升级后会拿百炼的 key 去调 deepseek 的型号,变成一次必然失败的调用。
        store.put(FamilyConfigService.K_LLM_PRIMARY_VENDOR, "qwen");
        store.put(FamilyConfigService.K_LLM_MODEL, "deepseek-chat");

        LlmSettings s = LlmSettings.load(config(), 1L);

        assertThat(s.primary().platform()).isEqualTo(LlmCatalog.P_DASHSCOPE);
        assertThat(s.primary().model()).isNull();                       // 自动
        assertThat(s.primary().resolvedModel()).isEqualTo("qwen-plus"); // 系列默认
    }

    @Test
    void legacyVisionOff_becomesSwitchOff_notAFakeModelName() {
        // v1.12 把「关掉截图识别」编码成 llm_vision_model=off(型号字段里塞了一个假型号)。
        // 拆成独立开关后:开关关、型号回到默认,用户重新打开不用再选一次。
        store.put(FamilyConfigService.K_LLM_VISION_MODEL, "off");

        LlmSettings s = LlmSettings.load(config(), 1L);

        assertThat(s.visionEnabled()).isFalse();
        assertThat(s.vision().resolvedModel()).isEqualTo("qwen-vl-max");
        assertThat(s.vision().model()).isNotEqualTo("off");
    }

    // ---------------- 新键优先 ----------------

    @Test
    void newKeysPresent_legacyKeysIgnored() {
        store.put(FamilyConfigService.K_LLM_PRIMARY_VENDOR, "deepseek");   // 旧键留着(回滚用)
        store.put(FamilyConfigService.K_LLM_MODEL, "deepseek-reasoner");
        store.put(FamilyConfigService.K_LLM_PLATFORM, "ark");              // 新键说了算
        store.put(FamilyConfigService.K_LLM_FAMILY, "doubao");
        store.put(FamilyConfigService.K_LLM_MODEL_ID, "ep-20260815120000-abcde");

        LlmSettings s = LlmSettings.load(config(), 1L);

        assertThat(s.legacy()).isFalse();
        assertThat(s.primary().platform()).isEqualTo(LlmCatalog.P_ARK);
        assertThat(s.primary().family()).isEqualTo("doubao");
        assertThat(s.primary().model()).isEqualTo("ep-20260815120000-abcde");
        assertThat(s.backup()).isEmpty();                                  // 没配备选就是没有
        assertThat(s.chain()).hasSize(1);
    }

    @Test
    void backupIdenticalToPrimary_isNotTriedTwice() {
        store.put(FamilyConfigService.K_LLM_PLATFORM, "dashscope");
        store.put(FamilyConfigService.K_LLM_FAMILY, "qwen");
        store.put(FamilyConfigService.K_LLM_BACKUP_PLATFORM, "dashscope");
        store.put(FamilyConfigService.K_LLM_BACKUP_FAMILY, "qwen");

        assertThat(LlmSettings.load(config(), 1L).chain()).hasSize(1);
    }

    @Test
    void newVisionKeys_switchIsIndependentOfModel() {
        store.put(FamilyConfigService.K_LLM_VISION_PLATFORM, "ark");
        store.put(FamilyConfigService.K_LLM_VISION_FAMILY, "doubao-vision");
        store.put(FamilyConfigService.K_LLM_VISION_MODEL_ID, "ep-vision-0001");
        store.put(FamilyConfigService.K_LLM_VISION_ENABLED, "false");

        LlmSettings s = LlmSettings.load(config(), 1L);

        assertThat(s.visionEnabled()).isFalse();                       // 关着
        assertThat(s.vision().resolvedModel()).isEqualTo("ep-vision-0001");   // 但选择还在
    }
}
