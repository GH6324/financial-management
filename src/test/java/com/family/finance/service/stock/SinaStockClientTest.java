package com.family.finance.service.stock;

import com.family.finance.domain.stock.Market;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SinaStockClient 解析单测 · v0.3 FR-52a。
 *
 * <p>只测 parseResponse(不发 HTTP 请求 · 用真实新浪格式 mock body)。</p>
 */
class SinaStockClientTest {

    private final SinaStockClient client = new SinaStockClient(new RestTemplateBuilder());

    @Test
    void parsesAStockOneTicker() {
        String body = "var hq_str_sh600519=\"贵州茅台,1850.00,1840.00,1860.50,1870.00,1840.00,1860.50,1860.55,12345,22912345.00\";\n";
        Map<String, StockQuote> r = client.parseResponse(Market.CN, body);
        assertThat(r).hasSize(1);
        assertThat(r.get("600519").closePrice()).isEqualByComparingTo(new BigDecimal("1860.50"));
        assertThat(r.get("600519").currency()).isEqualTo("CNY");
        assertThat(r.get("600519").source()).isEqualTo("sina");
    }

    @Test
    void parsesUsStockOneTicker() {
        String body = "var hq_str_gb_baba=\"ALIBABA GRP,89.20,0.50,2026-05-11 16:00:00,89.50,88.50,89.80\";\n";
        Map<String, StockQuote> r = client.parseResponse(Market.US, body);
        assertThat(r).hasSize(1);
        assertThat(r.get("BABA").closePrice()).isEqualByComparingTo(new BigDecimal("89.20"));
        assertThat(r.get("BABA").currency()).isEqualTo("USD");
    }

    @Test
    void parsesHkStockOneTicker() {
        // hk 字段 0=英文 1=中文 2=今开 3=昨收 4=最高 5=最低 6=当前
        String body = "var hq_str_hk00700=\"TENCENT,腾讯控股,400.00,395.00,402.00,398.50,401.20,1234567,500000000.00\";\n";
        Map<String, StockQuote> r = client.parseResponse(Market.HK, body);
        assertThat(r).hasSize(1);
        assertThat(r.get("00700").closePrice()).isEqualByComparingTo(new BigDecimal("401.20"));
        assertThat(r.get("00700").currency()).isEqualTo("HKD");
    }

    @Test
    void parsesBatchMultipleTickersSameMarket() {
        String body = """
            var hq_str_sh600519="贵州茅台,1850,1840,1860.50,1870,1840,1860,1860,12345,22912";
            var hq_str_sh600036="招商银行,32,31,32.50,33,31,32,32,12345,395";
            """;
        Map<String, StockQuote> r = client.parseResponse(Market.CN, body);
        assertThat(r).hasSize(2);
        assertThat(r.get("600519").closePrice()).isEqualByComparingTo(new BigDecimal("1860.50"));
        assertThat(r.get("600036").closePrice()).isEqualByComparingTo(new BigDecimal("32.50"));
    }

    @Test
    void emptyPayloadSkippedAsSuspended() {
        // 停牌股 · 新浪返回空 payload
        String body = "var hq_str_sh999999=\"\";\n";
        assertThat(client.parseResponse(Market.CN, body)).isEmpty();
    }

    @Test
    void malformedPriceSkipped() {
        String body = "var hq_str_sh600519=\"NAME,abc,def,ghi\";\n";
        assertThat(client.parseResponse(Market.CN, body)).isEmpty();
    }

    @Test
    void emptyBodyReturnsEmpty() {
        assertThat(client.parseResponse(Market.CN, "")).isEmpty();
        assertThat(client.parseResponse(Market.CN, null)).isEmpty();
    }

    @Test
    void toSinaSymbolNormalizesPrefix() {
        assertThat(SinaStockClient.toSinaSymbol(Market.US, "BABA")).isEqualTo("gb_baba");
        assertThat(SinaStockClient.toSinaSymbol(Market.CN, "600519")).isEqualTo("sh600519");
        assertThat(SinaStockClient.toSinaSymbol(Market.CN, "000001")).isEqualTo("sz000001");
        assertThat(SinaStockClient.toSinaSymbol(Market.HK, "00700")).isEqualTo("hk00700");
    }

    @Test
    void fromSinaSymbolStripsPrefix() {
        assertThat(SinaStockClient.fromSinaSymbol(Market.US, "gb_baba")).isEqualTo("BABA");
        assertThat(SinaStockClient.fromSinaSymbol(Market.CN, "sh600519")).isEqualTo("600519");
        assertThat(SinaStockClient.fromSinaSymbol(Market.HK, "hk00700")).isEqualTo("00700");
    }
}
