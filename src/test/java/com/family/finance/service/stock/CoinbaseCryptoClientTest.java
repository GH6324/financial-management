package com.family.finance.service.stock;

import com.family.finance.domain.stock.Market;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CoinbaseCryptoClientTest {

    private final CoinbaseCryptoClient client =
            new CoinbaseCryptoClient(new RestTemplateBuilder(), new ObjectMapper());

    @Test
    void parsesBtcSpotPrice() {
        String body = "{\"data\":{\"amount\":\"65647.615\",\"base\":\"BTC\",\"currency\":\"USD\"}}";

        Map<String, StockQuote> quotes = client.parseResponse(Market.CRYPTO, body);

        assertThat(quotes).containsKey("BTC");
        StockQuote btc = quotes.get("BTC");
        assertThat(btc.market()).isEqualTo(Market.CRYPTO);
        assertThat(btc.closePrice()).isEqualByComparingTo(new BigDecimal("65647.615"));
        assertThat(btc.currency()).isEqualTo("USD");
        assertThat(btc.source()).isEqualTo("coinbase");
    }

    @Test
    void parsesUsdcSpotPrice() {
        String body = "{\"data\":{\"amount\":\"1\",\"base\":\"USDC\",\"currency\":\"USD\"}}";

        Map<String, StockQuote> quotes = client.parseResponse(Market.CRYPTO, body);

        assertThat(quotes).containsKey("USDC");
        assertThat(quotes.get("USDC").closePrice()).isEqualByComparingTo("1");
    }

    @Test
    void normalizesFutureCoinTickers() {
        assertThat(CoinbaseCryptoClient.normalize(" eth-usd ")).isEqualTo("ETH");
        assertThat(CoinbaseCryptoClient.normalize("ETH/USD")).isEqualTo("ETH");
        assertThat(CoinbaseCryptoClient.normalize("BTC-USDT")).isEqualTo("BTC");
        assertThat(CoinbaseCryptoClient.normalize("sol")).isEqualTo("SOL");
        assertThat(CoinbaseCryptoClient.normalize("usdt")).isEqualTo("USDT");
    }
}
