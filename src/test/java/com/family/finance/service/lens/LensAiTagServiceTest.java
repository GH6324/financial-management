package com.family.finance.service.lens;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** v1.1 · AI 推荐打标 · 白名单校验(枚举外丢弃 · platform 截 40 · 无 client 降级) */
class LensAiTagServiceTest {

    @Test
    void aiWhitelist_dropsNonEnumValues_andTruncatesPlatform() {
        LensAiTagService svc = new LensAiTagService(List.of(), new ObjectMapper());
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
        LensAiTagService svc = new LensAiTagService(List.of(), new ObjectMapper());
        assertThat(svc.available()).isFalse();                    // 入口降级隐藏
        assertThat(svc.suggest(List.of("x"))).isEmpty();
    }
}
