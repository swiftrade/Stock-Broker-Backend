package com.stockpredict.marketdata.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockpredict.common.time.MarketClock;
import com.stockpredict.marketdata.api.model.Bar;
import com.stockpredict.marketdata.api.model.SymbolMatch;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/** Polygon.io REST adapter ({@code https://api.polygon.io}). */
public class PolygonProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(PolygonProvider.class);
    private static final int PAGE_LIMIT = 50_000;
    private static final int MAX_PAGES = 50;
    private static final String SOURCE = "polygon";

    private final RestClient restClient;
    private final String apiKey;
    private final MarketClock clock;

    public PolygonProvider(RestClient restClient, String apiKey, MarketClock clock) {
        this.restClient = restClient;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.clock = clock;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public List<Bar> fetchDailyBars(String symbol, LocalDate from, LocalDate to) {
        return fetchAggregates(symbol, 1, "day", from, to).stream()
                .map(PolygonProvider::normalizeDailyBar)
                .toList();
    }

    /**
     * Enforces the shared daily-bar convention: the stored timestamp is
     * {@code 00:00Z} of the UTC session date regardless of the provider's exact
     * aggregate timestamp. See {@link com.stockpredict.common.time.BarTimestamps}.
     */
    private static Bar normalizeDailyBar(Bar bar) {
        return new Bar()
                .ts(com.stockpredict.common.time.BarTimestamps
                        .dailyBarTimestamp(com.stockpredict.common.time.BarTimestamps
                                .sessionDateOfDailyBar(bar.getTs().toInstant()))
                        .atOffset(ZoneOffset.UTC))
                .open(bar.getOpen())
                .high(bar.getHigh())
                .low(bar.getLow())
                .close(bar.getClose())
                .volume(bar.getVolume())
                .vwap(bar.getVwap())
                .tradeCount(bar.getTradeCount());
    }

    @Override
    public List<Bar> fetchIntradayBars(String symbol, LocalDate from, LocalDate to) {
        return fetchAggregates(symbol, 5, "minute", from, to);
    }

    private List<Bar> fetchAggregates(String symbol, int multiplier, String timespan,
                                      LocalDate from, LocalDate to) {
        String next = "/v2/aggs/ticker/" + encode(symbol) + "/range/" + multiplier + "/" + timespan
                + "/" + from + "/" + to
                + "?adjusted=true&sort=asc&limit=" + PAGE_LIMIT + "&apiKey=" + encode(apiKey);
        List<Bar> bars = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES && next != null; page++) {
            JsonNode response = get(next);
            JsonNode results = response.path("results");
            if (results.isArray()) {
                for (JsonNode node : results) {
                    bars.add(toBar(node));
                }
            }
            JsonNode nextUrl = response.path("next_url");
            next = nextUrl.isTextual() && !nextUrl.asText().isBlank()
                    ? nextUrl.asText() + "&apiKey=" + encode(apiKey)
                    : null;
        }
        return bars;
    }

    private JsonNode get(String uri) {
        JsonNode body = restClient.get().uri(uri).retrieve().body(JsonNode.class);
        if (body == null) {
            throw new IllegalStateException("Empty Polygon response for " + uri);
        }
        return body;
    }

    private static Bar toBar(JsonNode node) {
        return new Bar()
                .ts(Instant.ofEpochMilli(node.path("t").asLong()).atOffset(ZoneOffset.UTC))
                .open(node.path("o").asDouble())
                .high(node.path("h").asDouble())
                .low(node.path("l").asDouble())
                .close(node.path("c").asDouble())
                .volume(node.path("v").asLong())
                .vwap(node.hasNonNull("vw") ? node.get("vw").asDouble() : null)
                .tradeCount(node.hasNonNull("n") ? node.get("n").asLong() : null);
    }

    @Override
    public List<SymbolMatch> search(String query, int limit) {
        try {
            String uri = "/v3/reference/tickers?search=" + encode(query)
                    + "&active=true&limit=" + Math.max(1, limit)
                    + "&apiKey=" + encode(apiKey);
            JsonNode response = get(uri);
            List<SymbolMatch> matches = new ArrayList<>();
            for (JsonNode node : response.path("results")) {
                matches.add(new SymbolMatch()
                        .symbol(node.path("ticker").asText())
                        .name(node.path("name").asText(null))
                        .exchange(node.path("primary_exchange").asText(null)));
            }
            return matches;
        } catch (Exception ex) {
            log.warn("Polygon symbol search failed for '{}': {}", query, ex.toString());
            return List.of();
        }
    }

    @Override
    public Optional<QuoteSnapshot> fetchQuote(String symbol) {
        try {
            Double bid = null;
            Double ask = null;
            Double last = null;
            OffsetDateTime ts = null;

            JsonNode nbbo = tryGet("/v2/last/nbbo/" + encode(symbol) + "?apiKey=" + encode(apiKey));
            if (nbbo != null) {
                JsonNode results = nbbo.path("results");
                bid = doubleOrNull(results, "bid", "bp");
                ask = doubleOrNull(results, "ask", "ap");
                ts = timestampOrNull(results);
            }

            JsonNode trade = tryGet("/v2/last/trade/" + encode(symbol) + "?apiKey=" + encode(apiKey));
            if (trade != null) {
                JsonNode results = trade.path("results");
                last = doubleOrNull(results, "p", "price");
                if (ts == null) {
                    ts = timestampOrNull(results);
                }
            }

            if (bid == null && ask == null && last == null) {
                return Optional.empty();
            }
            double mid = last != null ? last
                    : bid != null && ask != null ? (bid + ask) / 2.0
                    : bid != null ? bid : ask;
            if (bid == null) {
                bid = mid;
            }
            if (ask == null) {
                ask = mid;
            }
            if (last == null) {
                last = mid;
            }
            if (ts == null) {
                ts = clock.now().atOffset(ZoneOffset.UTC);
            }
            return Optional.of(new QuoteSnapshot(bid, ask, last, ts));
        } catch (Exception ex) {
            log.warn("Polygon quote failed for {}: {}", symbol, ex.toString());
            return Optional.empty();
        }
    }

    private JsonNode tryGet(String uri) {
        try {
            return get(uri);
        } catch (Exception ex) {
            log.debug("Polygon request failed for {}: {}", uri, ex.toString());
            return null;
        }
    }

    private static Double doubleOrNull(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isNumber()) {
                return value.asDouble();
            }
        }
        return null;
    }

    private static OffsetDateTime timestampOrNull(JsonNode node) {
        JsonNode millis = node.get("t");
        if (millis != null && millis.isNumber()) {
            return Instant.ofEpochMilli(millis.asLong()).atOffset(ZoneOffset.UTC);
        }
        JsonNode nanos = node.get("sip_timestamp");
        if (nanos != null && nanos.isNumber()) {
            return Instant.ofEpochSecond(0, nanos.asLong()).atOffset(ZoneOffset.UTC);
        }
        return null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
