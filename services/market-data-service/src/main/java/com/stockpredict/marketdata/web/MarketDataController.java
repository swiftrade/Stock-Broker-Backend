package com.stockpredict.marketdata.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpredict.common.error.ApiException;
import com.stockpredict.common.error.ErrorCode;
import com.stockpredict.common.time.BarTimestamps;
import com.stockpredict.common.time.MarketClock;
import com.stockpredict.common.time.TradingCalendar;
import com.stockpredict.marketdata.api.MarketDataApi;
import com.stockpredict.marketdata.api.model.Bar;
import com.stockpredict.marketdata.api.model.BarSeries;
import com.stockpredict.marketdata.api.model.Quote;
import com.stockpredict.marketdata.api.model.QuoteList;
import com.stockpredict.marketdata.api.model.SymbolMatch;
import com.stockpredict.marketdata.api.model.SymbolSearchResult;
import com.stockpredict.marketdata.config.MarketDataProperties;
import com.stockpredict.marketdata.guard.GuardResult;
import com.stockpredict.marketdata.guard.IngestionQualityGuard;
import com.stockpredict.marketdata.guard.QuoteGuard;
import com.stockpredict.marketdata.ingest.BarRepository;
import com.stockpredict.marketdata.provider.MarketDataProvider;
import com.stockpredict.marketdata.provider.QuoteSnapshot;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Implements the frozen market-data contract on top of the bar store. */
@RestController
public class MarketDataController implements MarketDataApi {

    private static final Logger log = LoggerFactory.getLogger(MarketDataController.class);
    private static final String INTERVAL_DAILY = "1d";
    private static final String INTERVAL_INTRADAY = "5m";
    private static final int MAX_BARS = 2000;
    private static final int DEFAULT_DAILY_YEARS = 5;
    private static final int DEFAULT_INTRADAY_DAYS = 30;
    private static final int MAX_BATCH_SYMBOLS = 50;
    private static final Duration INTRADAY_STALE_AFTER = Duration.ofMinutes(30);
    private static final java.util.regex.Pattern SYMBOL_PATTERN =
            java.util.regex.Pattern.compile("^[A-Z][A-Z0-9.-]{0,9}$");

    private final MarketDataProvider provider;
    private final BarRepository repository;
    private final IngestionQualityGuard guard;
    private final QuoteGuard quoteGuard;
    private final TradingCalendar tradingCalendar;
    private final MarketClock clock;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final MarketDataProperties properties;

    public MarketDataController(MarketDataProvider provider,
                                BarRepository repository,
                                IngestionQualityGuard guard,
                                QuoteGuard quoteGuard,
                                TradingCalendar tradingCalendar,
                                MarketClock clock,
                                StringRedisTemplate redis,
                                ObjectMapper objectMapper,
                                MarketDataProperties properties) {
        this.provider = provider;
        this.repository = repository;
        this.guard = guard;
        this.quoteGuard = quoteGuard;
        this.tradingCalendar = tradingCalendar;
        this.clock = clock;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * Serves cached bars, filling the cache from the provider on a miss. The
     * cache-miss write is best-effort and bounded to symbols already known to
     * the service ({@code symbol_universe} membership or existing bars); unknown
     * user-requested symbols are never persisted.
     */
    @Override
    public ResponseEntity<BarSeries> getHistory(String symbol, String interval, LocalDate from, LocalDate to) {
        String normalized = interval == null ? INTERVAL_DAILY : interval.trim().toLowerCase(Locale.ROOT);
        if (!INTERVAL_DAILY.equals(normalized) && !INTERVAL_INTRADAY.equals(normalized)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Unsupported interval: " + interval);
        }
        LocalDate effectiveTo = to == null ? clock.todayEt() : to;
        LocalDate effectiveFrom = from == null
                ? (INTERVAL_DAILY.equals(normalized)
                        ? effectiveTo.minusYears(DEFAULT_DAILY_YEARS)
                        : effectiveTo.minusDays(DEFAULT_INTRADAY_DAYS))
                : from;
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "'from' must not be after 'to'");
        }

        List<Bar> bars = loadFromDatabase(symbol, normalized, effectiveFrom, effectiveTo);
        String source = "cache";
        if (bars.isEmpty()) {
            bars = ingestOnDemand(symbol, normalized, effectiveFrom, effectiveTo);
            source = provider.source();
            if (bars.isEmpty()) {
                throw ApiException.notFound(symbol);
            }
        }
        boolean stale = isStale(normalized, bars);
        return ResponseEntity.ok(new BarSeries()
                .symbol(symbol)
                .interval(BarSeries.IntervalEnum.fromValue(normalized))
                .source(source)
                .stale(stale)
                .bars(cap(bars, MAX_BARS)));
    }

    @Override
    public ResponseEntity<Quote> getQuote(String symbol) {
        Quote cached = readCachedQuote(symbol);
        if (isValidQuote(cached)) {
            return ResponseEntity.ok(cached);
        }
        Optional<QuoteSnapshot> snapshot = fetchQuoteQuietly(symbol);
        if (snapshot.isPresent() && quoteGuard.isValid(snapshot.get())) {
            Quote quote = toQuote(symbol, snapshot.get(), false);
            cacheQuote(symbol, quote);
            return ResponseEntity.ok(quote);
        }
        Optional<Bar> latest = repository.latestDailyBar(symbol);
        if (latest.isEmpty() || latest.get().getClose() == null || latest.get().getClose() <= 0) {
            throw ApiException.notFound(symbol);
        }
        Bar bar = latest.get();
        Quote fallback = new Quote()
                .symbol(symbol)
                .bid(bar.getClose())
                .ask(bar.getClose())
                .last(bar.getClose())
                .ts(bar.getTs())
                .stale(true);
        return ResponseEntity.ok(fallback);
    }

    @Override
    public ResponseEntity<QuoteList> getQuotes(String symbols) {
        List<String> requested = parseSymbols(symbols);
        if (requested.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "'symbols' must contain at least one symbol");
        }
        List<Quote> quotes = new ArrayList<>(requested.size());
        for (String symbol : requested) {
            try {
                quotes.add(getQuote(symbol).getBody());
            } catch (ApiException ex) {
                if (ex.errorCode() != ErrorCode.NOT_FOUND) {
                    throw ex;
                }
            }
        }
        return ResponseEntity.ok(new QuoteList().quotes(quotes));
    }

    @Override
    public ResponseEntity<SymbolSearchResult> searchSymbols(String q, Integer limit) {
        int max = limit == null ? 10 : Math.min(Math.max(limit, 1), 50);
        String query = q == null ? "" : q.trim();

        LinkedHashSet<String> databaseSymbols = new LinkedHashSet<>(repository.searchSymbols(query, max));
        List<SymbolMatch> matches = new ArrayList<>();
        for (String symbol : databaseSymbols) {
            matches.add(new SymbolMatch().symbol(symbol));
        }
        try {
            for (SymbolMatch match : provider.search(query, max)) {
                if (matches.size() >= max) {
                    break;
                }
                boolean duplicate = matches.stream()
                        .anyMatch(existing -> existing.getSymbol().equalsIgnoreCase(match.getSymbol()));
                if (!duplicate) {
                    matches.add(match);
                }
            }
        } catch (Exception ex) {
            log.warn("Provider symbol search failed for '{}': {}", query, ex.toString());
        }
        if (matches.size() > max) {
            matches = new ArrayList<>(matches.subList(0, max));
        }
        return ResponseEntity.ok(new SymbolSearchResult().results(matches));
    }

    private List<Bar> loadFromDatabase(String symbol, String interval, LocalDate from, LocalDate to) {
        return INTERVAL_DAILY.equals(interval)
                ? repository.loadDaily(symbol, from, to)
                : repository.loadIntraday(symbol, from, to);
    }

    private List<Bar> ingestOnDemand(String symbol, String interval, LocalDate from, LocalDate to) {
        if (!properties.isAllowOnDemandIngestion()) {
            log.debug("On-demand ingestion disabled; not fetching {} {}", symbol, interval);
            return List.of();
        }
        if (!repository.isUniverseSymbol(symbol) && !repository.hasBars(symbol)) {
            log.debug("On-demand ingestion skipped for unknown symbol {}", symbol);
            return List.of();
        }
        try {
            List<Bar> fetched = INTERVAL_DAILY.equals(interval)
                    ? provider.fetchDailyBars(symbol, from, to)
                    : provider.fetchIntradayBars(symbol, from, to);
            GuardResult result = guard.validate(fetched, interval, false);
            List<Bar> accepted = result.accepted();
            if (!accepted.isEmpty()) {
                if (INTERVAL_DAILY.equals(interval)) {
                    repository.upsertDaily(symbol, provider.source(), accepted);
                } else {
                    repository.upsertIntraday(symbol, provider.source(), accepted);
                }
            }
            return accepted;
        } catch (Exception ex) {
            log.warn("On-demand ingestion failed for {} {}: {}", symbol, interval, ex.toString());
            return List.of();
        }
    }

    private boolean isStale(String interval, List<Bar> bars) {
        if (bars.isEmpty()) {
            return false;
        }
        OffsetDateTime last = bars.get(bars.size() - 1).getTs();
        if (last == null) {
            return false;
        }
        if (INTERVAL_DAILY.equals(interval)) {
            LocalDate lastDate = BarTimestamps.sessionDateOfDailyBar(last.toInstant());
            return lastDate.isBefore(tradingCalendar.previousTradingDay(clock.todayEt()));
        }
        if (!tradingCalendar.isMarketOpen(clock.now())) {
            return false;
        }
        return last.toInstant().isBefore(clock.now().minus(INTRADAY_STALE_AFTER));
    }

    private static List<Bar> cap(List<Bar> bars, int max) {
        if (bars.size() <= max) {
            return bars;
        }
        List<Bar> sampled = new ArrayList<>(max);
        double step = bars.size() / (double) max;
        for (int i = 0; i < max; i++) {
            sampled.add(bars.get((int) (i * step)));
        }
        Bar last = bars.get(bars.size() - 1);
        if (!last.equals(sampled.get(sampled.size() - 1))) {
            sampled.set(sampled.size() - 1, last);
        }
        return sampled;
    }

    private Optional<QuoteSnapshot> fetchQuoteQuietly(String symbol) {
        try {
            return provider.fetchQuote(symbol);
        } catch (Exception ex) {
            log.warn("Quote provider failed for {}: {}", symbol, ex.toString());
            return Optional.empty();
        }
    }

    private static Quote toQuote(String symbol, QuoteSnapshot snapshot, boolean stale) {
        return new Quote()
                .symbol(symbol)
                .bid(snapshot.bid())
                .ask(snapshot.ask())
                .last(snapshot.last())
                .ts(snapshot.ts())
                .stale(stale);
    }

    private Quote readCachedQuote(String symbol) {
        try {
            String cached = redis.opsForValue().get(quoteKey(symbol));
            if (cached != null && !cached.isBlank()) {
                return objectMapper.readValue(cached, Quote.class);
            }
        } catch (Exception ex) {
            log.debug("Quote cache read failed for {}: {}", symbol, ex.toString());
        }
        return null;
    }

    private void cacheQuote(String symbol, Quote quote) {
        try {
            String payload = objectMapper.writeValueAsString(quote);
            redis.opsForValue().set(quoteKey(symbol), payload,
                    Duration.ofSeconds(Math.max(1, properties.getQuoteCacheTtlSeconds())));
        } catch (Exception ex) {
            log.debug("Quote cache write failed for {}: {}", symbol, ex.toString());
        }
    }

    private static String quoteKey(String symbol) {
        return "quote:" + symbol;
    }

    private static boolean isValidQuote(Quote quote) {
        return quote != null
                && quote.getBid() != null && quote.getBid() > 0
                && quote.getAsk() != null && quote.getAsk() > 0
                && quote.getLast() != null && quote.getLast() > 0
                && quote.getAsk() >= quote.getBid();
    }

    private static List<String> parseSymbols(String symbols) {
        if (symbols == null || symbols.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String raw : symbols.split(",")) {
            String symbol = raw.trim().toUpperCase(Locale.ROOT);
            if (symbol.isEmpty()) {
                continue;
            }
            if (!SYMBOL_PATTERN.matcher(symbol).matches()) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "Invalid symbol: " + raw.trim());
            }
            unique.add(symbol);
            if (unique.size() > MAX_BATCH_SYMBOLS) {
                throw new ApiException(ErrorCode.BAD_REQUEST,
                        "At most " + MAX_BATCH_SYMBOLS + " symbols per request");
            }
        }
        return new ArrayList<>(unique);
    }
}
