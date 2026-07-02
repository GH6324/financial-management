package com.family.finance.service.stock;

import com.family.finance.domain.stock.Market;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CoinGeckoCryptoClientTest {

    private final CoinGeckoCryptoClient client =
            new CoinGeckoCryptoClient(new RestTemplateBuilder(), new ObjectMapper());

    @Test
    void parsesSymbolPriceResponse() {
        String body = """
                {"btc":{"usd":61316.45286001806,"last_updated_at":1783025152},
                 "usdc":{"usd":0.9997985443604398,"last_updated_at":1783025149}}
                """;

        Map<String, StockQuote> quotes = client.parseResponse(Market.CRYPTO, body);

        assertThat(quotes).containsKeys("BTC", "USDC");
        assertThat(quotes.get("BTC").source()).isEqualTo("coingecko");
        assertThat(quotes.get("BTC").currency()).isEqualTo("USD");
        assertThat(quotes.get("USDC").closePrice()).isEqualByComparingTo("0.9997985443604398");
    }
}
