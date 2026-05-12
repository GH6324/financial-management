package com.family.finance.service.stock;

import com.family.finance.domain.stock.Market;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TencentStockClient 解析单测 · v0.3 FR-52a 备源。
 */
class TencentStockClientTest {

    private final TencentStockClient client = new TencentStockClient(new RestTemplateBuilder());

    @Test
    void parsesAStockOneTicker() {
        // 腾讯格式:1~名称~code~当前价~...
        String body = "v_sh600519=\"1~贵州茅台~600519~1860.50~1830~1870~1845~12345~12345\";\n";
        Map<String, StockQuote> r = client.parseResponse(Market.CN, body);
        assertThat(r).hasSize(1);
        assertThat(r.get("600519").closePrice()).isEqualByComparingTo(new BigDecimal("1860.50"));
        assertThat(r.get("600519").currency()).isEqualTo("CNY");
        assertThat(r.get("600519").source()).isEqualTo("tencent");
    }

    @Test
    void parsesUsStockOneTicker() {
        String body = "v_usBABA=\"1~阿里巴巴~BABA~89.20~88.70~89.50~88.50\";\n";
        Map<String, StockQuote> r = client.parseResponse(Market.US, body);
        assertThat(r).hasSize(1);
        assertThat(r.get("BABA").closePrice()).isEqualByComparingTo(new BigDecimal("89.20"));
    }

    @Test
    void parsesHkStockOneTicker() {
        String body = "v_hk00700=\"1~腾讯控股~00700~401.20~395.00~402.00~398.50\";\n";
        Map<String, StockQuote> r = client.parseResponse(Market.HK, body);
        assertThat(r).hasSize(1);
        assertThat(r.get("00700").closePrice()).isEqualByComparingTo(new BigDecimal("401.20"));
    }

    @Test
    void emptyPayloadSkipped() {
        String body = "v_sh999999=\"\";\n";
        assertThat(client.parseResponse(Market.CN, body)).isEmpty();
    }

    @Test
    void shortPayloadSkipped() {
        String body = "v_sh600519=\"1~bad\";\n";
        assertThat(client.parseResponse(Market.CN, body)).isEmpty();
    }

    @Test
    void toTencentSymbolNormalizesPrefix() {
        assertThat(TencentStockClient.toTencentSymbol(Market.US, "baba")).isEqualTo("usBABA");
        assertThat(TencentStockClient.toTencentSymbol(Market.CN, "600519")).isEqualTo("sh600519");
        assertThat(TencentStockClient.toTencentSymbol(Market.HK, "00700")).isEqualTo("hk00700");
    }
}
