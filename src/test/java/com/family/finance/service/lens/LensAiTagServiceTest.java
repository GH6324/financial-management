package com.family.finance.service.lens;

import com.family.finance.service.checkup.llm.LlmRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** v1.1 · AI 推荐打标 · 白名单校验(枚举外丢弃 · platform 截 40 · 无 client 降级) */
class LensAiTagServiceTest {

    /** v1.13 · 一个客户端都没装的真路由:编排后没有候选 → 入口判灰、调用空手而归 */
    private static LlmRouter emptyRouter() {
        var cs = mock(com.family.finance.service.config.FamilyConfigService.class);
        when(cs.getString(anyLong(), anyString(), any())).thenAnswer(i -> i.getArgument(2));
        return new LlmRouter(List.of(), cs);
    }

    @Test
    void aiWhitelist_dropsNonEnumValues_andTruncatesPlatform() {
        LensAiTagService svc = new LensAiTagService(emptyRouter(), new ObjectMapper());
        String raw = """
                前置废话 {"宁德时代":{"platform":"富途证券","industry":"NEW_ENERGY","assetClass":"EQUITY"},
                 "神秘资产":{"platform":"%s","industry":"半导体","assetClass":"BOND"}} 后置废话
                """.formatted("超长平台名".repeat(20));
        var out = svc.parseAndWhitelist(raw, List.of("宁德时代", "神秘资产", "没出现的"));
        assertThat(out.get("宁德时代").industry()).isEqualTo("NEW_ENERGY");
        assertThat(out.get("宁德时代").assetClass()).isEqualTo("EQUITY");
        assertThat(out.get("神秘资产").industry()).isNull();      // 枚举外「半导体」丢弃
        assertThat(out.get("神秘资产").assetClass()).isNull();    // 枚举外「BOND」丢弃
        assertThat(out.get("神秘资产").platform()).hasSize(40);   // platform 截 40
        assertThat(out).doesNotContainKey("没出现的");
    }

    @Test
    void aiUnavailable_whenNoClients() {
        LensAiTagService svc = new LensAiTagService(emptyRouter(), new ObjectMapper());
        assertThat(svc.available(1L)).isFalse();                    // 入口降级隐藏
        assertThat(svc.suggest(1L, List.of("x"))).isEmpty();
    }
}
