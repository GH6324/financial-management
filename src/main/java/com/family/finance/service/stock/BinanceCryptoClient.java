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
 * Binance public spot ticker client for crypto holdings.
 *
 * <p>Uses public market data only. Prices are requested as SYMBOLUSDT pairs and
 * stored as USD-equivalent quotes for portfolio valuation.</p>
 */
@Component
@Slf4j
public class BinanceCryptoClient implements StockClient {

    private static final String BASE = "https://data-api.binance.vision/api/v3/ticker/price?symbol=";
    private static final String QUOTE_SUFFIX = "USDT";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public BinanceCryptoClient(RestTemplateBuilder builder, ObjectMapper objectMapper) {
        this.restTemplate = builder
            .setConnectTimeout(Duration.ofSeconds(3))
            .setReadTimeout(Duration.ofSeconds(5))
            .defaultHeader(HttpHeaders.USER_AGENT, "finance-self-hosted")
            .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public String sourceTag() {
        return "binance";
    }

    @Override
    public Map<String, StockQuote> fetchBatch(Market market, List<String> tickers) {
        if (market != Market.CRYPTO || tickers == null || tickers.isEmpty()) return Map.of();
        Map<String, StockQuote> result = new HashMap<>();
        for (String raw : tickers) {
            String ticker = CoinbaseCryptoClient.normalize(raw);
            if (ticker.isBlank()) continue;
            if (QUOTE_SUFFIX.equals(ticker)) {
                result.put(ticker, new StockQuote(ticker, Market.CRYPTO, BigDecimal.ONE, "USD", sourceTag()));
                continue;
            }
            try {
                result.putAll(parseResponse(market, fetchTickerBody(ticker + QUOTE_SUFFIX)));
            } catch (Exception e) {
                log.warn("binance fetch exception · ticker={} err={}", ticker, e.toString());
            }
        }
        return result;
    }

    @Override
    public Map<String, StockQuote> parseResponse(Market market, String body) {
        if (market != Market.CRYPTO || body == null || body.isBlank()) return Map.of();
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.has("code") && root.has("msg")) return Map.of();
            String symbol = root.path("symbol").asText("").toUpperCase(Locale.ROOT);
            String rawPrice = root.path("price").asText("");
            if (!symbol.endsWith(QUOTE_SUFFIX) || rawPrice.isBlank()) return Map.of();
            String ticker = symbol.substring(0, symbol.length() - QUOTE_SUFFIX.length());
            BigDecimal price = new BigDecimal(rawPrice);
            if (price.signum() <= 0) return Map.of();
            return Map.of(ticker, new StockQuote(ticker, Market.CRYPTO, price, "USD", sourceTag()));
        } catch (Exception e) {
            log.warn("binance parse exception · err={}", e.toString());
            return Map.of();
        }
    }

    private String fetchTickerBody(String symbol) {
        URI url = URI.create(BASE + symbol);
        ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, null, String.class);
        if (resp.getStatusCode().isError() || resp.getBody() == null) {
            log.warn("binance fetch HTTP error · status={} symbol={}", resp.getStatusCode(), symbol);
            return "";
        }
        return resp.getBody();
    }
}
