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
        assertThat(QwenVisionClient.parseMoney("¥42,318.60")).isEqualByComparingTo("42318.60");
        assertThat(QwenVisionClient.parseMoney("274,067.44")).isEqualByComparingTo("274067.44");
        assertThat(QwenVisionClient.parseMoney("5.17")).isEqualByComparingTo("5.17");
        assertThat(QwenVisionClient.parseMoney("null")).isNull();
        assertThat(QwenVisionClient.parseMoney(null)).isNull();
        assertThat(QwenVisionClient.parseMoney("")).isNull();
    }

    @Test
    void parse_toleratesJsonFence_andSkipsBlankNames() {
        String raw = "```json\n[{\"name\":\"余额宝\",\"code\":\"000198\",\"marketValue\":\"274,067.44\",\"confidence\":\"high\"},"
                + "{\"name\":\"上银慧元利\",\"marketValue\":null,\"confidence\":\"low\"},"
                + "{\"name\":\"\",\"marketValue\":\"1\"}]\n```";
        List<QwenVisionClient.ParsedRow> rows = QwenVisionClient.parse(raw);
        assertThat(rows).hasSize(2);   // 空名跳过
        assertThat(rows.get(0).name()).isEqualTo("余额宝");
        assertThat(rows.get(0).code()).isEqualTo("000198");
        assertThat(rows.get(0).marketValue()).isEqualByComparingTo("274067.44");
        assertThat(rows.get(1).confidence()).isEqualTo("low");   // 截断行诚实标疑
        assertThat(rows.get(1).marketValue()).isNull();          // 不编造
    }

    @Test
    void parse_emptyOrGarbage_returnsEmpty() {
        assertThat(QwenVisionClient.parse(null)).isEmpty();
        assertThat(QwenVisionClient.parse("对不起我看不清")).isEmpty();
    }

    @Test
    void normalize_stripsWhitespaceAndFullWidth() {
        assertThat(HoldingImportService.normalize("易方达 蓝筹精选混合"))
                .isEqualTo(HoldingImportService.normalize("易方达蓝筹精选混合"));
        assertThat(HoldingImportService.normalize("天弘国证Ａ５０"))
                .isEqualTo(HoldingImportService.normalize("天弘国证a50"));
        assertThat(HoldingImportService.normalize(null)).isEmpty();
    }
}
