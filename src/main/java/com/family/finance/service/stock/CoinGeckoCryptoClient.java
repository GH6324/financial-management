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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * CoinGecko public price client for crypto holdings.
 *
 * <p>Uses symbol lookup with the top-ranked token for each symbol. This keeps
 * common holdings such as BTC / ETH / USDC keyless and batchable.</p>
 */
@Component
@Slf4j
public class CoinGeckoCryptoClient implements StockClient {

    private static final String BASE = "https://api.coingecko.com/api/v3/simple/price";
    private static final String QUOTE_CURRENCY = "usd";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public CoinGeckoCryptoClient(RestTemplateBuilder builder, ObjectMapper objectMapper) {
        this.restTemplate = builder
            .setConnectTimeout(Duration.ofSeconds(3))
            .setReadTimeout(Duration.ofSeconds(5))
            .defaultHeader(HttpHeaders.USER_AGENT, "finance-self-hosted")
            .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public String sourceTag() {
        return "coingecko";
    }

    @Override
    public Map<String, StockQuote> fetchBatch(Market market, List<String> tickers) {
        if (market != Market.CRYPTO || tickers == null || tickers.isEmpty()) return Map.of();
        String symbols = tickers.stream()
            .map(CoinbaseCryptoClient::normalize)
            .filter(s -> !s.isBlank())
            .map(s -> s.toLowerCase(Locale.ROOT))
            .collect(Collectors.collectingAndThen(
                Collectors.toCollection(LinkedHashSet::new),
                s -> String.join(",", s)));
        if (symbols.isBlank()) return Map.of();

        URI url = URI.create(BASE
            + "?symbols=" + symbols
            + "&vs_currencies=" + QUOTE_CURRENCY
            + "&include_tokens=top"
            + "&include_last_updated_at=true"
            + "&precision=full");
        try {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, null, String.class);
            if (resp.getStatusCode().isError() || resp.getBody() == null) {
                log.warn("coingecko fetch HTTP error · status={} symbols={}", resp.getStatusCode(), symbols);
                return Map.of();
            }
            return parseResponse(market, resp.getBody());
        } catch (Exception e) {
            log.warn("coingecko fetch exception · symbols={} err={}", symbols, e.toString());
            return Map.of();
        }
    }

    @Override
    public Map<String, StockQuote> parseResponse(Market market, String body) {
        if (market != Market.CRYPTO || body == null || body.isBlank()) return Map.of();
        try {
            JsonNode root = objectMapper.readTree(body);
            java.util.Map<String, StockQuote> result = new java.util.HashMap<>();
            root.fields().forEachRemaining(entry -> {
                JsonNode data = entry.getValue();
                if (data == null || !data.has(QUOTE_CURRENCY)) return;
                BigDecimal price = data.path(QUOTE_CURRENCY).decimalValue();
                if (price == null || price.signum() <= 0) return;
                String ticker = entry.getKey().toUpperCase(Locale.ROOT);
                result.put(ticker, new StockQuote(
                    ticker, Market.CRYPTO, price, QUOTE_CURRENCY.toUpperCase(Locale.ROOT), sourceTag()));
            });
            return result;
        } catch (Exception e) {
            log.warn("coingecko parse exception · err={}", e.toString());
            return Map.of();
        }
    }
}
