package com.family.finance.service.stock;

import com.family.finance.domain.stock.Market;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceCryptoClientTest {

    private final BinanceCryptoClient client =
            new BinanceCryptoClient(new RestTemplateBuilder(), new ObjectMapper());

    @Test
    void parsesTickerPriceResponse() {
        String body = "{\"symbol\":\"BTCUSDT\",\"price\":\"61314.92000000\"}";

        Map<String, StockQuote> quotes = client.parseResponse(Market.CRYPTO, body);

        assertThat(quotes).containsKey("BTC");
        assertThat(quotes.get("BTC").source()).isEqualTo("binance");
        assertThat(quotes.get("BTC").currency()).isEqualTo("USD");
        assertThat(quotes.get("BTC").closePrice()).isEqualByComparingTo("61314.92000000");
    }

    @Test
    void ignoresRestrictedLocationError() {
        String body = "{\"code\":0,\"msg\":\"Service unavailable from a restricted location\"}";

        assertThat(client.parseResponse(Market.CRYPTO, body)).isEmpty();
    }
}
