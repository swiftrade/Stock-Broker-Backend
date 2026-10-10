package com.stockpredict.marketdata.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stockpredict.common.error.ApiException;
import com.stockpredict.common.error.ErrorCode;
import com.stockpredict.common.time.MarketClock;
import com.stockpredict.common.time.TradingCalendar;
import com.stockpredict.marketdata.api.model.Bar;
import com.stockpredict.marketdata.api.model.BarSeries;
import com.stockpredict.marketdata.api.model.Quote;
import com.stockpredict.marketdata.api.model.QuoteList;
import com.stockpredict.marketdata.api.model.SymbolMatch;
import com.stockpredict.marketdata.api.model.SymbolSearchResult;
import com.stockpredict.marketdata.config.MarketDataProperties;
import com.stockpredict.marketdata.guard.IngestionQualityGuard;
import com.stockpredict.marketdata.guard.QuoteGuard;
import com.stockpredict.marketdata.ingest.BarRepository;
import com.stockpredict.marketdata.provider.MarketDataProvider;
import com.stockpredict.marketdata.provider.QuoteSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.ResponseEntity;

class MarketDataControllerTest {

    private static final Instant NOW = Instant.parse("2026-06-10T15:00:00Z");
    private static final LocalDate PREVIOUS_TRADING_DAY = LocalDate.of(2026, 6, 9);

    private MarketDataProvider provider;
    private BarRepository repository;
    private ValueOperations<String, String> valueOps;
    private ObjectMapper objectMapper;
    private MarketDataProperties properties;
    private MarketDataController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MarketClock clock = new MarketClock(Clock.fixed(NOW, ZoneOffset.UTC));
        provider = mock(MarketDataProvider.class);
        repository = mock(BarRepository.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        properties = new MarketDataProperties();
        controller = new MarketDataController(provider, repository,
                new IngestionQualityGuard(properties, clock), new QuoteGuard(properties),
                new TradingCalendar(), clock, redis, objectMapper, properties);
    }

    @Test
    void historyServesBarsFromDatabase() {
        when(repository.loadDaily(eq("AAPL"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(dailyBar(PREVIOUS_TRADING_DAY.minusDays(1), 100.0),
                        dailyBar(PREVIOUS_TRADING_DAY, 101.0)));

        ResponseEntity<BarSeries> response = controller.getHistory("AAPL", "1d", null, null);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getSymbol()).isEqualTo("AAPL");
        assertThat(response.getBody().getInterval()).isEqualTo(BarSeries.IntervalEnum._1D);
        assertThat(response.getBody().getSource()).isEqualTo("cache");
        assertThat(response.getBody().getStale()).isFalse();
        assertThat(response.getBody().getBars()).hasSize(2);
        verify(provider, never()).fetchDailyBars(anyString(), any(), any());
    }

    @Test
    void historyMarksSeriesStaleWhenLatestBarIsTooOld() {
        when(repository.loadDaily(eq("AAPL"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(dailyBar(PREVIOUS_TRADING_DAY.minusDays(5), 100.0)));

        ResponseEntity<BarSeries> response = controller.getHistory("AAPL", "1d", null, null);

        assertThat(response.getBody().getStale()).isTrue();
    }

    @Test
    void historyFallsBackToProviderAndIngestsWhenDatabaseEmpty() {
        List<Bar> fetched = List.of(dailyBar(PREVIOUS_TRADING_DAY, 100.0));
        when(repository.loadDaily(eq("AAPL"), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of());
        when(repository.isUniverseSymbol("AAPL")).thenReturn(true);
        when(provider.fetchDailyBars(eq("AAPL"), any(LocalDate.class), any(LocalDate.class))).thenReturn(fetched);
        when(provider.source()).thenReturn("polygon");

        ResponseEntity<BarSeries> response = controller.getHistory("AAPL", "1d", null, null);

        assertThat(response.getBody().getSource()).isEqualTo("polygon");
        assertThat(response.getBody().getStale()).isFalse();
        assertThat(response.getBody().getBars()).hasSize(1);
        verify(repository).upsertDaily(eq("AAPL"), eq("polygon"), anyList());
    }

    @Test
    void historyUnknownSymbolReturnsNotFound() {
        when(repository.loadDaily(eq("ZZZZ"), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of());
        when(repository.isUniverseSymbol("ZZZZ")).thenReturn(false);
        when(repository.hasBars("ZZZZ")).thenReturn(false);

        assertThatThrownBy(() -> controller.getHistory("ZZZZ", "1d", null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void onDemandIngestionSkipsSymbolsUnknownToTheUniverse() {
        when(repository.loadDaily(eq("ZZZZ"), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of());
        when(repository.isUniverseSymbol("ZZZZ")).thenReturn(false);
        when(repository.hasBars("ZZZZ")).thenReturn(false);

        assertThatThrownBy(() -> controller.getHistory("ZZZZ", "1d", null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(provider, never()).fetchDailyBars(anyString(), any(), any());
        verify(repository, never()).upsertDaily(anyString(), anyString(), anyList());
    }

    @Test
    void onDemandIngestionIsSkippedWhenDisabled() {
        properties.setAllowOnDemandIngestion(false);
        when(repository.loadDaily(eq("AAPL"), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of());

        assertThatThrownBy(() -> controller.getHistory("AAPL", "1d", null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(provider, never()).fetchDailyBars(anyString(), any(), any());
    }

    @Test
    void historyRejectsUnsupportedInterval() {
        assertThatThrownBy(() -> controller.getHistory("AAPL", "1h", null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void historyIntradayMarksStaleDuringMarketHours() {
        Bar lastBar = new Bar()
                .ts(NOW.minusSeconds(45 * 60).atOffset(ZoneOffset.UTC))
                .open(100.0).high(101.0).low(99.0).close(100.5).volume(1_000L);
        when(repository.loadIntraday(eq("AAPL"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(lastBar));

        ResponseEntity<BarSeries> response = controller.getHistory("AAPL", "5m", null, null);

        assertThat(response.getBody().getInterval()).isEqualTo(BarSeries.IntervalEnum._5M);
        assertThat(response.getBody().getStale()).isTrue();
    }

    @Test
    void quoteUsesProviderAndCachesIt() {
        when(valueOps.get("quote:AAPL")).thenReturn(null);
        when(provider.fetchQuote("AAPL"))
                .thenReturn(Optional.of(new QuoteSnapshot(100.0, 100.1, 100.05, NOW.atOffset(ZoneOffset.UTC))));

        ResponseEntity<Quote> response = controller.getQuote("AAPL");

        assertThat(response.getBody().getSymbol()).isEqualTo("AAPL");
        assertThat(response.getBody().getBid()).isEqualTo(100.0);
        assertThat(response.getBody().getAsk()).isEqualTo(100.1);
        assertThat(response.getBody().getLast()).isEqualTo(100.05);
        assertThat(response.getBody().getStale()).isFalse();
        verify(valueOps).set(eq("quote:AAPL"), anyString(), eq(Duration.ofSeconds(60)));
    }

    @Test
    void quoteIsServedFromCache() throws Exception {
        Quote cached = new Quote()
                .symbol("AAPL").bid(1.0).ask(1.1).last(1.05)
                .ts(NOW.atOffset(ZoneOffset.UTC)).stale(false);
        when(valueOps.get("quote:AAPL")).thenReturn(objectMapper.writeValueAsString(cached));

        ResponseEntity<Quote> response = controller.getQuote("AAPL");

        assertThat(response.getBody().getLast()).isEqualTo(1.05);
        verify(provider, never()).fetchQuote(anyString());
    }

    @Test
    void quoteFallsBackToLatestDailyCloseAsStale() {
        when(valueOps.get("quote:AAPL")).thenReturn(null);
        when(provider.fetchQuote("AAPL")).thenReturn(Optional.empty());
        Bar latest = dailyBar(PREVIOUS_TRADING_DAY, 123.45);
        when(repository.latestDailyBar("AAPL")).thenReturn(Optional.of(latest));

        ResponseEntity<Quote> response = controller.getQuote("AAPL");

        assertThat(response.getBody().getStale()).isTrue();
        assertThat(response.getBody().getLast()).isEqualTo(123.45);
        assertThat(response.getBody().getTs()).isEqualTo(latest.getTs());
    }

    @Test
    void zeroProviderQuoteIsRejectedAndFallsBackToTheDailyClose() {
        when(valueOps.get("quote:AAPL")).thenReturn(null);
        when(provider.fetchQuote("AAPL"))
                .thenReturn(Optional.of(new QuoteSnapshot(0.0, 0.0, 0.0, NOW.atOffset(ZoneOffset.UTC))));
        when(repository.latestDailyBar("AAPL")).thenReturn(Optional.of(dailyBar(PREVIOUS_TRADING_DAY, 123.45)));

        ResponseEntity<Quote> response = controller.getQuote("AAPL");

        assertThat(response.getBody().getLast()).isEqualTo(123.45);
        assertThat(response.getBody().getStale()).isTrue();
        verify(valueOps, never()).set(eq("quote:AAPL"), anyString(), any(Duration.class));
    }

    @Test
    void crossedProviderQuoteIsRejected() {
        when(valueOps.get("quote:AAPL")).thenReturn(null);
        when(provider.fetchQuote("AAPL"))
                .thenReturn(Optional.of(new QuoteSnapshot(101.0, 100.0, 100.5, NOW.atOffset(ZoneOffset.UTC))));
        when(repository.latestDailyBar("AAPL")).thenReturn(Optional.of(dailyBar(PREVIOUS_TRADING_DAY, 99.0)));

        ResponseEntity<Quote> response = controller.getQuote("AAPL");

        assertThat(response.getBody().getLast()).isEqualTo(99.0);
        assertThat(response.getBody().getStale()).isTrue();
    }

    @Test
    void batchQuotesReturnsUsableQuotesAndSkipsUnknownSymbols() {
        when(valueOps.get("quote:AAPL")).thenReturn(null);
        when(valueOps.get("quote:MSFT")).thenReturn(null);
        when(valueOps.get("quote:ZZZZ")).thenReturn(null);
        when(provider.fetchQuote("AAPL"))
                .thenReturn(Optional.of(new QuoteSnapshot(100.0, 100.1, 100.05, NOW.atOffset(ZoneOffset.UTC))));
        when(provider.fetchQuote("MSFT"))
                .thenReturn(Optional.of(new QuoteSnapshot(200.0, 200.2, 200.1, NOW.atOffset(ZoneOffset.UTC))));
        when(provider.fetchQuote("ZZZZ")).thenReturn(Optional.empty());
        when(repository.latestDailyBar("ZZZZ")).thenReturn(Optional.empty());

        ResponseEntity<QuoteList> response = controller.getQuotes("AAPL, msft,ZZZZ");

        assertThat(response.getBody().getQuotes()).extracting(Quote::getSymbol)
                .containsExactly("AAPL", "MSFT");
    }

    @Test
    void batchQuotesRejectsInvalidSymbols() {
        assertThatThrownBy(() -> controller.getQuotes("AAPL,$BAD"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
    }

    @Test
    void quoteUnknownSymbolReturnsNotFound() {
        when(valueOps.get("quote:ZZZZ")).thenReturn(null);
        when(provider.fetchQuote("ZZZZ")).thenReturn(Optional.empty());
        when(repository.latestDailyBar("ZZZZ")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getQuote("ZZZZ"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void searchMergesDatabaseAndProviderMatchesWithoutDuplicates() {
        when(repository.searchSymbols("AA", 10)).thenReturn(List.of("AAPL", "AAL"));
        when(provider.search("AA", 10)).thenReturn(List.of(
                new SymbolMatch().symbol("AAPL").name("Apple Inc.").exchange("XNAS"),
                new SymbolMatch().symbol("MSFT").name("Microsoft Corporation").exchange("XNAS")));

        ResponseEntity<SymbolSearchResult> response = controller.searchSymbols("AA", null);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getResults())
                .extracting(SymbolMatch::getSymbol)
                .containsExactly("AAPL", "AAL", "MSFT");
    }

    private static Bar dailyBar(LocalDate date, double close) {
        return new Bar()
                .ts(date.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime())
                .open(close - 1.0)
                .high(close + 1.0)
                .low(close - 2.0)
                .close(close)
                .volume(1_000L);
    }
}
