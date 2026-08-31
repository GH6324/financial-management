package com.family.finance.service.holdingimport;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.4 · 持仓截图导入纯函数单测(视觉 JSON 解析/市值规整 + 归一化匹配键)。
 * 不依赖 Spring / DB / 网络。
 */
class HoldingImportUnitTest {

    @Test
    void parseMoney_stripsCommaAndCurrency_notArithmetic() {
        assertThat(VisionLlmClient.parseMoney("¥42,318.60")).isEqualByComparingTo("42318.60");
        assertThat(VisionLlmClient.parseMoney("274,067.44")).isEqualByComparingTo("274067.44");
        assertThat(VisionLlmClient.parseMoney("5.17")).isEqualByComparingTo("5.17");
        assertThat(VisionLlmClient.parseMoney("null")).isNull();
        assertThat(VisionLlmClient.parseMoney(null)).isNull();
        assertThat(VisionLlmClient.parseMoney("")).isNull();
    }

    @Test
    void parse_toleratesJsonFence_andSkipsBlankNames() {
        String raw = "```json\n[{\"name\":\"余额宝\",\"code\":\"000198\",\"marketValue\":\"274,067.44\",\"confidence\":\"high\"},"
                + "{\"name\":\"上银慧元利\",\"marketValue\":null,\"confidence\":\"low\"},"
                + "{\"name\":\"\",\"marketValue\":\"1\"}]\n```";
        List<VisionLlmClient.ParsedRow> rows = VisionLlmClient.parse(raw);
        assertThat(rows).hasSize(2);   // 空名跳过
        assertThat(rows.get(0).name()).isEqualTo("余额宝");
        assertThat(rows.get(0).code()).isEqualTo("000198");
        assertThat(rows.get(0).marketValue()).isEqualByComparingTo("274067.44");
        assertThat(rows.get(1).confidence()).isEqualTo("low");   // 截断行诚实标疑
        assertThat(rows.get(1).marketValue()).isNull();          // 不编造
    }

    @Test
    void parse_emptyOrGarbage_returnsEmpty() {
        assertThat(VisionLlmClient.parse(null)).isEmpty();
        assertThat(VisionLlmClient.parse("对不起我看不清")).isEmpty();
    }

    @Test
    void normalize_stripsWhitespaceAndFullWidth() {
        assertThat(HoldingImportService.normalize("易方达 蓝筹精选混合"))
                .isEqualTo(HoldingImportService.normalize("易方达蓝筹精选混合"));
        assertThat(HoldingImportService.normalize("天弘国证Ａ５０"))
                .isEqualTo(HoldingImportService.normalize("天弘国证a50"));
        assertThat(HoldingImportService.normalize(null)).isEmpty();
    }

    // ──────────────────────────────────────────────────────────────
    // v1.19.4 · 上游失败要翻成用户能照着做的话
    //
    // 线上真实撞到的是「免费额度耗尽」(403 AllocationQuota.FreeTierOnly),而当时的兜底
    // 文案是「识别失败,请重试」—— 那是**错的建议**:重试一万次也不会好,必须去控制台。
    // 一句让人做无用功的提示比没有提示更浪费时间,所以这些分类要钉住。
    // ──────────────────────────────────────────────────────────────

    private static String friendly(String message) {
        return HoldingImportService.friendly(new RuntimeException(message));
    }

    @Test
    void friendly_quotaExhausted_tellsUserToTopUp_notRetry() {
        // 这就是 prod 日志里那条原文(403 的 body)
        String real = "403 : \"{\"error\":{\"message\":\"Free quota exhausted. To continue accessing "
                + "the model on a paid basis, please add funds or disable the \\\"use free tier only\\\" "
                + "mode in the management console.\",\"type\":\"AllocationQuota.FreeTierOnly\"}}\"";
        String out = friendly(new RuntimeException(real).getMessage());
        assertThat(out).contains("额度用完");
        assertThat(out).contains("充值");
        // 关键:不能退化成那句兜底的「识别失败,请重试」——
        // 额度问题重试永远不会好,那是让人白等的建议。
        // (文案里的「重试没用」是**正确**表达,不在防范之列。)
        assertThat(out).doesNotContain("请重试");
    }

    @Test
    void friendly_quotaBeatsForbidden_orderMatters() {
        // 配额错误本身就是 403。若先匹配 403,会退化成笼统的「被拒绝」,把可操作性丢掉。
        assertThat(friendly("403 Forbidden AllocationQuota.FreeTierOnly")).contains("额度用完");
        // 纯 403(没有配额特征)才走「被拒绝」那条
        assertThat(friendly("403 Forbidden NoPermission")).contains("拒绝");
    }

    @Test
    void friendly_classifiesEachUpstreamFailure() {
        assertThat(friendly("429 Too Many Requests")).contains("限流");
        assertThat(friendly("401 Unauthorized InvalidApiKey")).contains("key");
        assertThat(friendly("404 model_not_found")).contains("型号");
        assertThat(friendly("java.net.SocketTimeoutException: Read timed out")).contains("超时");
        assertThat(friendly("java.net.ConnectException: Connection refused")).contains("连不上");
    }

    @Test
    void friendly_unknownFallsBackToRetry_andNullSafe() {
        // 认不出来的才说「请重试」——那是兜底,不是万能答案
        assertThat(friendly("something weird happened")).isEqualTo("识别失败,请重试");
        assertThat(HoldingImportService.friendly(new RuntimeException((String) null)))
                .isEqualTo("识别失败,请重试");
        assertThat(HoldingImportService.friendly(null)).isEqualTo("识别失败,请重试");
    }

    @Test
    void scanErrorIsItsOwnState_notReview() {
        // 「全都没识别出来」不能和「识别完了,请核对」共用一个状态 ——
        // 前者的比对表里每条持仓都写着「卖出?」,而用户看不出那是假的。
        assertThat(com.family.finance.domain.holdingimport.HoldingImport.SCAN_ERROR)
                .isNotEqualTo(com.family.finance.domain.holdingimport.HoldingImport.REVIEW);
    }
}
