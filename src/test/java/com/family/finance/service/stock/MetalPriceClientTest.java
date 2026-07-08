package com.family.finance.service.stock;

import com.family.finance.domain.stock.Market;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MetalPriceClient 解析单测 · v0.14 · 用真实新浪 gds_/hf_ 格式 mock body(不发 HTTP)。
 */
class MetalPriceClientTest {

    private final MetalPriceClient client = new MetalPriceClient(new RestTemplateBuilder());

    @Test
    void parsesSgeGold_perGramCny() {
        String body = "var hq_str_gds_AU9999=\"892.00,0,891.00,892.00,893.00,890.50,20:17:02,901.07,892.00,2658,6.00,128.00,2026-07-08,黄金99\";\n";
        Map<String, StockQuote> r = client.parseResponse(Market.METAL, body);
        assertThat(r).containsKey("AU9999");
        assertThat(r.get("AU9999").closePrice()).isEqualByComparingTo("892.00"); // 元/克,不变
        assertThat(r.get("AU9999").currency()).isEqualTo("CNY");
        assertThat(r.get("AU9999").source()).isEqualTo("sina-metal");
        assertThat(r.get("AU9999").market()).isEqualTo(Market.METAL);
    }

    @Test
    void parsesSgeSilver_normalizedPerGram() {
        // SGE 白银原报价 元/千克(14400) → 归一每克 14.4
        String body = "var hq_str_gds_AGTD=\"14400.00,0,14380.00,14417.00,14450.00,14351.00,20:17:06,14818.00,14420.00,4436,60.00,62.00,2026-07-08,白银\";\n";
        Map<String, StockQuote> r = client.parseResponse(Market.METAL, body);
        assertThat(r.get("AGTD").closePrice()).isEqualByComparingTo("14.4");
        assertThat(r.get("AGTD").currency()).isEqualTo("CNY");
    }

    @Test
    void parsesInternationalGold_perGramUsd() {
        String body = "var hq_str_hf_XAU=\"4070.94,4105.700,4070.94,4071.29,4133.80,4040.61,20:17:00,4105.70,4102.03,0,0,0,2026-07-08,伦敦金\";\n";
        Map<String, StockQuote> r = client.parseResponse(Market.METAL, body);
        // 每盎司 4070.94 → 每克(与 MetalUnit 同口径)
        assertThat(r.get("XAU").closePrice())
                .isEqualByComparingTo(MetalUnit.normalizeToPerGram("XAU", new BigDecimal("4070.94")));
        assertThat(r.get("XAU").currency()).isEqualTo("USD");
    }

    @Test
    void parsesBatchMixedSources() {
        String body = """
            var hq_str_gds_AU9999="892.00,0,891,892,893,890.5,20:17,901,892,1,1,1,2026-07-08,金";
            var hq_str_hf_XAG="58.72,59.952,58.72,58.77,61.00,58.04,20:17,59.95,59.82,0,0,0,2026-07-08,伦敦银";
            """;
        Map<String, StockQuote> r = client.parseResponse(Market.METAL, body);
        assertThat(r).containsKeys("AU9999", "XAG");
        assertThat(r.get("XAG").currency()).isEqualTo("USD");
    }

    @Test
    void emptyPayloadSkipped() {
        assertThat(client.parseResponse(Market.METAL, "var hq_str_gds_AU9999=\"\";\n")).isEmpty();
        assertThat(client.parseResponse(Market.METAL, "")).isEmpty();
        assertThat(client.parseResponse(Market.METAL, null)).isEmpty();
    }
}
