package com.family.finance.service.stock;

import com.family.finance.domain.stock.Market;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Coinbase public spot price client for crypto holdings.
 *
 * <p>Uses public market data only. It does not connect to a user's exchange
 * account and does not require an API key.</p>
 */
@Component
@Slf4j
public class CoinbaseCryptoClient implements StockClient {

    private static final String BASE = "https://api.coinbase.com/v2/prices/";
    private static final String QUOTE_CURRENCY = "USD";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public CoinbaseCryptoClient(RestTemplateBuilder builder, ObjectMapper objectMapper) {
        this.restTemplate = builder
            .setConnectTimeout(Duration.ofSeconds(3))
            .setReadTimeout(Duration.ofSeconds(5))
            .defaultHeader(HttpHeaders.USER_AGENT, "finance-self-hosted")
            .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public String sourceTag() {
        return "coinbase";
    }

    @Override
    public Map<String, StockQuote> fetchBatch(Market market, List<String> tickers) {
        if (market != Market.CRYPTO || tickers == null || tickers.isEmpty()) return Map.of();
        Map<String, StockQuote> result = new HashMap<>();
        for (String raw : tickers) {
            String ticker = normalize(raw);
            if (ticker.isBlank()) continue;
            try {
                result.putAll(parseResponse(market, fetchSpotBody(ticker)));
            } catch (Exception e) {
                log.warn("coinbase fetch exception · ticker={} err={}", ticker, e.toString());
            }
        }
        return result;
    }

    @Override
    public Map<String, StockQuote> parseResponse(Market market, String body) {
        if (market != Market.CRYPTO || body == null || body.isBlank()) return Map.of();
        try {
            JsonNode data = objectMapper.readTree(body).path("data");
            String base = normalize(data.path("base").asText(""));
            String currency = data.path("currency").asText(QUOTE_CURRENCY).toUpperCase(Locale.ROOT);
            String amount = data.path("amount").asText("");
            if (base.isBlank() || amount.isBlank()) return Map.of();
            BigDecimal price = new BigDecimal(amount);
            if (price.signum() <= 0) return Map.of();
            return Map.of(base, new StockQuote(base, Market.CRYPTO, price, currency, sourceTag()));
        } catch (Exception e) {
            log.warn("coinbase parse exception · err={}", e.toString());
            return Map.of();
        }
    }

    private String fetchSpotBody(String ticker) {
        URI url = URI.create(BASE + ticker + "-" + QUOTE_CURRENCY + "/spot");
        ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, null, String.class);
        if (resp.getStatusCode().isError() || resp.getBody() == null) {
            log.warn("coinbase fetch HTTP error · status={} ticker={}", resp.getStatusCode(), ticker);
            return "";
        }
        return resp.getBody();
    }

    static String normalize(String ticker) {
        if (ticker == null) return "";
        String t = ticker.trim().toUpperCase(Locale.ROOT);
        t = t.replaceAll("[-/](USD|USDT)$", "");
        return t.replaceAll("[^A-Z0-9]", "");
    }
}
