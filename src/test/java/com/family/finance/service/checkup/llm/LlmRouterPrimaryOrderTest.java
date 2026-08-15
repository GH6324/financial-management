package com.family.finance.service.checkup.llm;

import com.family.finance.service.config.FamilyConfigService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.13 FR-360/363 · <b>主备编排收口到 {@link LlmRouter}</b> 的回归护栏
 * (承接 v0.14 的 {@code LlmVendorOrderingTest} —— 那时排序还散在各调用方手里)。
 *
 * <p>盯三件事:
 * <ol>
 *   <li>顺序只来自配置 —— 新键有就按新键,旧家庭按 {@code llm_primary_vendor} 派生,
 *       都不来自 Spring 的 bean 顺序({@code @Order} 已删)。</li>
 *   <li>「注定失败」的候选在<b>出网之前</b>就被剔掉:方舟没填型号、平台没实现、客户端不可用。</li>
 *   <li>主选失败 / 输出不被接受 → 自动落到备选。</li>
 * </ol>
 */
class LlmRouterPrimaryOrderTest {

    // ---------------- 脚手架 ----------------

    /** 用一张 map 冒充 family_config;没有的键返回调用方给的 codeDefault(和真实实现一致) */
    private static FamilyConfigService config(Map<String, String> values) {
        FamilyConfigService cfg = mock(FamilyConfigService.class);
        when(cfg.getString(anyLong(), anyString(), any())).thenAnswer(inv -> {
            String key = inv.getArgument(1);
            return values.containsKey(key) ? values.get(key) : inv.getArgument(2);
        });
        when(cfg.getBoolean(anyLong(), anyString(), anyBoolean())).thenAnswer(inv -> {
            String key = inv.getArgument(1);
            return values.containsKey(key) ? Boolean.parseBoolean(values.get(key)) : inv.getArgument(2);
        });
        return cfg;
    }

    /** 记录被调用顺序的假客户端 · reply 为 null 表示这一家必抛(模拟出网失败) */
    private static final class FakeClient implements LlmClient {
        private final String platform;
        private final boolean available;
        private final String reply;
        private final List<String> log;

        FakeClient(String platform, boolean available, String reply, List<String> log) {
            this.platform = platform;
            this.available = available;
            this.reply = reply;
            this.log = log;
        }

        @Override public String platform() { return platform; }
        @Override public boolean available() { return available; }

        @Override public String chat(LlmInvocation invocation, String systemPrompt, String userPrompt) {
            log.add(invocation.label());
            if (reply == null) throw new IllegalStateException("模拟 " + platform + " 调用失败");
            return reply;
        }
    }

    private static final List<String> NOLOG = new ArrayList<>();

    private static LlmClient ok(String platform) { return new FakeClient(platform, true, platform + "-ok", NOLOG); }
    private static LlmClient down(String platform) { return new FakeClient(platform, false, "never", NOLOG); }

    private static List<String> labels(List<LlmInvocation> plan) {
        return plan.stream().map(LlmInvocation::label).toList();
    }

    // ---------------- 1 · 顺序来自配置 ----------------

    @Test
    void newConfig_primaryThenBackup_orderComesFromConfig() {
        LlmRouter router = new LlmRouter(
                // bean 顺序故意与配置相反 —— 如果谁又按注入顺序遍历,这条会红
                List.of(ok(LlmCatalog.P_DASHSCOPE), ok(LlmCatalog.P_DEEPSEEK)),
                config(new HashMap<>(Map.of(
                        FamilyConfigService.K_LLM_PLATFORM, "deepseek",
                        FamilyConfigService.K_LLM_FAMILY, "deepseek",
                        FamilyConfigService.K_LLM_BACKUP_PLATFORM, "dashscope",
                        FamilyConfigService.K_LLM_BACKUP_FAMILY, "qwen"))));

        assertThat(labels(router.plan(1L)))
                .containsExactly("deepseek/deepseek:auto", "dashscope/qwen:auto");
    }

    @Test
    void legacyVendorDeepseek_deepseekFirst() {
        // 旧家庭(没有 llm_platform)· v0.14 的「主选=DeepSeek」必须原样生效,另一家自动成备选
        LlmRouter router = new LlmRouter(
                List.of(ok(LlmCatalog.P_DASHSCOPE), ok(LlmCatalog.P_DEEPSEEK)),
                config(new HashMap<>(Map.of(FamilyConfigService.K_LLM_PRIMARY_VENDOR, "deepseek"))));

        assertThat(labels(router.plan(1L)))
                .containsExactly("deepseek/deepseek:auto", "dashscope/qwen:auto");
    }

    @Test
    void legacyDefault_dashscopeFirst() {
        LlmRouter router = new LlmRouter(
                List.of(ok(LlmCatalog.P_DEEPSEEK), ok(LlmCatalog.P_DASHSCOPE)),
                config(new HashMap<>()));

        assertThat(labels(router.plan(1L)))
                .containsExactly("dashscope/qwen:auto", "deepseek/deepseek:auto");
    }

    // ---------------- 2 · 注定失败的候选出网前就剔掉 ----------------

    @Test
    void arkWithoutModel_isDroppedBeforeNetwork() {
        // 方舟系列没有可预置型号 → 没填就不自洽 → 不该占用户的等待时间去 404 一次
        LlmRouter router = new LlmRouter(
                List.of(ok(LlmCatalog.P_ARK), ok(LlmCatalog.P_DASHSCOPE)),
                config(new HashMap<>(Map.of(
                        FamilyConfigService.K_LLM_PLATFORM, "ark",
                        FamilyConfigService.K_LLM_FAMILY, "doubao",
                        FamilyConfigService.K_LLM_BACKUP_PLATFORM, "dashscope",
                        FamilyConfigService.K_LLM_BACKUP_FAMILY, "qwen"))));

        assertThat(labels(router.plan(1L))).containsExactly("dashscope/qwen:auto");
    }

    @Test
    void arkWithExplicitEndpointId_isACandidate() {
        LlmRouter router = new LlmRouter(
                List.of(ok(LlmCatalog.P_ARK)),
                config(new HashMap<>(Map.of(
                        FamilyConfigService.K_LLM_PLATFORM, "ark",
                        FamilyConfigService.K_LLM_FAMILY, "doubao",
                        FamilyConfigService.K_LLM_MODEL_ID, "ep-20260815120000-abcde"))));

        assertThat(labels(router.plan(1L))).containsExactly("ark/doubao:ep-20260815120000-abcde");
    }

    @Test
    void unavailableClient_isDropped_andAvailableGoesFalseWhenNothingLeft() {
        // key 没配 / 熔断中 → available()=false
        LlmRouter router = new LlmRouter(
                List.of(down(LlmCatalog.P_DASHSCOPE), ok(LlmCatalog.P_DEEPSEEK)),
                config(new HashMap<>()));
        assertThat(labels(router.plan(1L))).containsExactly("deepseek/deepseek:auto");

        LlmRouter none = new LlmRouter(
                List.of(down(LlmCatalog.P_DASHSCOPE), down(LlmCatalog.P_DEEPSEEK)),
                config(new HashMap<>()));
        assertThat(none.plan(1L)).isEmpty();
        assertThat(none.available(1L)).isFalse();       // 「AI 解读」按钮据此判灰
    }

    @Test
    void platformWithoutClientImpl_isDropped() {
        // 只装了百炼的实现 → 配置指向方舟时不能编成候选(否则 NPE / 空转)
        LlmRouter router = new LlmRouter(
                List.of(ok(LlmCatalog.P_DASHSCOPE)),
                config(new HashMap<>(Map.of(
                        FamilyConfigService.K_LLM_PLATFORM, "ark",
                        FamilyConfigService.K_LLM_FAMILY, "doubao",
                        FamilyConfigService.K_LLM_MODEL_ID, "ep-x"))));

        assertThat(router.plan(1L)).isEmpty();
        assertThat(router.available(1L)).isFalse();
    }

    // ---------------- 3 · 主选不成就切备选 ----------------

    @Test
    void invoke_failsOverToBackup_whenPrimaryThrows() {
        List<String> called = new ArrayList<>();
        LlmRouter router = new LlmRouter(
                List.of(new FakeClient(LlmCatalog.P_DASHSCOPE, true, null, called),      // 主选必抛
                        new FakeClient(LlmCatalog.P_DEEPSEEK, true, "备选答的", called)),
                config(new HashMap<>()));

        var outcome = router.invoke(1L, "sys", "user");

        assertThat(outcome).isPresent();
        assertThat(outcome.get().text()).isEqualTo("备选答的");
        assertThat(outcome.get().used().platform()).isEqualTo(LlmCatalog.P_DEEPSEEK);
        assertThat(called).containsExactly("dashscope/qwen:auto", "deepseek/deepseek:auto");
    }

    @Test
    void invoke_handlerRejectingOutput_triesNextCandidate() {
        List<String> called = new ArrayList<>();
        LlmRouter router = new LlmRouter(
                List.of(new FakeClient(LlmCatalog.P_DASHSCOPE, true, "不合规", called),
                        new FakeClient(LlmCatalog.P_DEEPSEEK, true, "合规", called)),
                config(new HashMap<>()));

        // Handler 返回 null = 不收这次输出(体检的合规校验、AI 打标解析空都是这个形态)
        String accepted = router.invoke(1L, "sys", "user",
                (inv, raw, ms) -> "合规".equals(raw) ? raw : null);

        assertThat(accepted).isEqualTo("合规");
        assertThat(called).containsExactly("dashscope/qwen:auto", "deepseek/deepseek:auto");
    }

    @Test
    void invoke_allCandidatesFail_returnsEmpty() {
        List<String> called = new ArrayList<>();
        LlmRouter router = new LlmRouter(
                List.of(new FakeClient(LlmCatalog.P_DASHSCOPE, true, null, called),
                        new FakeClient(LlmCatalog.P_DEEPSEEK, true, null, called)),
                config(new HashMap<>()));

        assertThat(router.invoke(1L, "sys", "user")).isEmpty();
        assertThat(called).hasSize(2);        // 两家都试过了,不是第一家失败就收工
    }
}
