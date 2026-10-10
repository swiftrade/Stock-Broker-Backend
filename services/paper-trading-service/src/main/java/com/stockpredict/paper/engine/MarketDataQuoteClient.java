package com.stockpredict.paper.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Calls the market-data-service quote endpoint. Any transport/parse failure is
 * treated as "no quote" so the composite provider can fall back to the DB.
 */
public class MarketDataQuoteClient implements QuoteProvider {

    private static final Logger log = LoggerFactory.getLogger(MarketDataQuoteClient.class);

    private final RestClient restClient;

    public MarketDataQuoteClient(String baseUrl, long timeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    public MarketDataQuoteClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Optional<Quote> quote(String symbol) {
        try {
            QuotePayload payload = restClient.get()
                    .uri("/api/v1/stocks/{symbol}/quote", symbol)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(QuotePayload.class);
            if (payload == null || payload.last() == null || payload.last() <= 0) {
                return Optional.empty();
            }
            double last = payload.last();
            double bid = payload.bid() == null || payload.bid() <= 0 ? last : payload.bid();
            double ask = payload.ask() == null || payload.ask() <= 0 ? last : payload.ask();
            Instant ts = payload.ts() == null ? Instant.now() : payload.ts().toInstant();
            return Optional.of(new Quote(payload.symbol() == null ? symbol : payload.symbol(),
                    bid, ask, last, ts, Boolean.TRUE.equals(payload.stale())));
        } catch (RestClientException ex) {
            log.debug("Quote lookup failed for {}: {}", symbol, ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Map<String, Quote> quotes(Collection<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return Map.of();
        }
        try {
            QuoteListPayload payload = restClient.get()
                    .uri("/api/v1/stocks/quotes?symbols={symbols}", String.join(",", symbols))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(QuoteListPayload.class);
            if (payload == null || payload.quotes() == null) {
                return fallbackBatch(symbols);
            }
            Map<String, Quote> result = new LinkedHashMap<>();
            for (QuotePayload quote : payload.quotes()) {
                if (quote.symbol() != null && quote.last() != null && quote.last() > 0) {
                    result.put(quote.symbol(), toQuote(quote));
                }
            }
            return result;
        } catch (RestClientException ex) {
            // Older market-data pods may not expose the batch endpoint yet.
            log.debug("Batch quote lookup failed ({}); falling back to per-symbol", ex.getMessage());
            return fallbackBatch(symbols);
        }
    }

    private Map<String, Quote> fallbackBatch(Collection<String> symbols) {
        Map<String, Quote> result = new LinkedHashMap<>();
        for (String symbol : symbols) {
            quote(symbol).ifPresent(quote -> result.put(symbol, quote));
        }
        return result;
    }

    private static Quote toQuote(QuotePayload payload) {
        double last = payload.last();
        double bid = payload.bid() == null || payload.bid() <= 0 ? last : payload.bid();
        double ask = payload.ask() == null || payload.ask() <= 0 ? last : payload.ask();
        Instant ts = payload.ts() == null ? Instant.now() : payload.ts().toInstant();
        return new Quote(payload.symbol(), bid, ask, last, ts, Boolean.TRUE.equals(payload.stale()));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record QuotePayload(String symbol, Double bid, Double ask, Double last, OffsetDateTime ts, Boolean stale) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record QuoteListPayload(List<QuotePayload> quotes) {
    }
}
