package com.stockpredict.marketdata.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stockpredict.common.time.MarketClock;
import com.stockpredict.marketdata.api.model.Bar;
import com.stockpredict.marketdata.config.MarketDataProperties;
import com.stockpredict.marketdata.guard.IngestionQualityGuard;
import com.stockpredict.marketdata.provider.MarketDataProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IngestionServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-10T15:00:00Z");

    private MarketDataProvider provider;
    private BarRepository repository;
    private BarsUpdatedPublisher publisher;
    private IngestionService service;

    @BeforeEach
    void setUp() {
        MarketClock clock = new MarketClock(Clock.fixed(NOW, ZoneOffset.UTC));
        provider = mock(MarketDataProvider.class);
        repository = mock(BarRepository.class);
        publisher = mock(BarsUpdatedPublisher.class);
        service = new IngestionService(provider, repository, new IngestionQualityGuard(new MarketDataProperties(), clock),
                publisher, clock, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        when(provider.source()).thenReturn("polygon");
    }

    @Test
    void ingestsDailyBarsAndPublishesOneEvent() {
        when(provider.fetchDailyBars(eq("AAPL"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(bar(NOW.minusSeconds(600), 100.0), bar(NOW.minusSeconds(300), 101.0)));

        IngestionResult result = service.ingestDaily(List.of("AAPL"), 5);

        assertThat(result.symbolsRequested()).isEqualTo(1);
        assertThat(result.symbolsSucceeded()).isEqualTo(1);
        assertThat(result.symbolsFailed()).isZero();
        assertThat(result.barsAccepted()).isEqualTo(2);
        assertThat(result.barsRejected()).isZero();

        verify(repository).upsertDaily(eq("AAPL"), eq("polygon"), anyList());
        verify(publisher).publish(eq("AAPL"), eq("1d"), anyList(), eq("polygon"));
    }

    @Test
    void guardRejectionsAreCountedAndNotPersisted() {
        when(provider.fetchDailyBars(eq("AAPL"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(bar(NOW.minusSeconds(600), 100.0), bar(NOW.minusSeconds(300), -5.0)));

        IngestionResult result = service.ingestDaily(List.of("AAPL"), 5);

        assertThat(result.barsAccepted()).isEqualTo(1);
        assertThat(result.barsRejected()).isEqualTo(1);
        verify(repository).upsertDaily(eq("AAPL"), eq("polygon"),
                org.mockito.ArgumentMatchers.argThat(bars -> bars.size() == 1));
    }

    @Test
    void perSymbolFailureDoesNotAbortTheBatch() {
        when(provider.fetchDailyBars(eq("AAPL"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(bar(NOW.minusSeconds(600), 100.0)));
        when(provider.fetchDailyBars(eq("BAD"), any(LocalDate.class), any(LocalDate.class)))
                .thenThrow(new IllegalStateException("provider down"));

        IngestionResult result = service.ingestDaily(List.of("AAPL", "BAD"), 5);

        assertThat(result.symbolsRequested()).isEqualTo(2);
        assertThat(result.symbolsSucceeded()).isEqualTo(1);
        assertThat(result.symbolsFailed()).isEqualTo(1);
        assertThat(result.barsAccepted()).isEqualTo(1);
        verify(repository).upsertDaily(eq("AAPL"), eq("polygon"), anyList());
        verify(repository, never()).upsertDaily(eq("BAD"), any(), anyList());
    }

    @Test
    void emptyProviderResultDoesNotUpsertOrPublish() {
        when(provider.fetchDailyBars(eq("AAPL"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());

        IngestionResult result = service.ingestDaily(List.of("AAPL"), 5);

        assertThat(result.barsAccepted()).isZero();
        assertThat(result.symbolsSucceeded()).isEqualTo(1);
        verify(repository, never()).upsertDaily(any(), any(), anyList());
        verify(publisher, never()).publish(any(), any(), anyList(), any());
    }

    @Test
    void publishesOneEventPerSymbolWithAcceptedBars() {
        when(provider.fetchDailyBars(anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(bar(NOW.minusSeconds(600), 100.0)));

        IngestionResult result = service.ingestDaily(List.of("AAPL", "MSFT"), 5);

        assertThat(result.symbolsSucceeded()).isEqualTo(2);
        verify(publisher, times(2)).publish(any(), eq("1d"), anyList(), eq("polygon"));
    }

    @Test
    void intradayUsesTheIntradayTable() {
        when(provider.fetchIntradayBars(eq("AAPL"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(bar(NOW.minusSeconds(300), 100.0)));

        IngestionResult result = service.ingestIntraday(List.of("AAPL"), 1);

        assertThat(result.barsAccepted()).isEqualTo(1);
        verify(repository).upsertIntraday(eq("AAPL"), eq("polygon"), anyList());
        verify(publisher).publish(eq("AAPL"), eq("5m"), anyList(), eq("polygon"));
    }

    @Test
    void intradayBackfillKeepsOldBars() {
        when(provider.fetchIntradayBars(eq("AAPL"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(bar(NOW.minusSeconds(48 * 3600), 100.0)));

        IngestionResult result = service.ingestIntraday(List.of("AAPL"), 30);

        assertThat(result.barsAccepted()).isEqualTo(1);
        assertThat(result.barsRejected()).isZero();
    }

    @Test
    void incrementalDailyFetchStartsAfterTheLatestStoredBar() {
        when(repository.latestDailyTimestamp("AAPL")).thenReturn(Optional.of(LocalDate.of(2026, 6, 5)));
        when(provider.fetchDailyBars(eq("AAPL"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(bar(NOW.minusSeconds(600), 100.0)));

        service.ingestDaily(List.of("AAPL"), 5);

        ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(provider).fetchDailyBars(eq("AAPL"), fromCaptor.capture(), eq(LocalDate.of(2026, 6, 10)));
        assertThat(fromCaptor.getValue()).isEqualTo(LocalDate.of(2026, 6, 6));
    }

    @Test
    void neverSeenSymbolUsesTheFullBackfillWindow() {
        when(repository.latestDailyTimestamp("NEW")).thenReturn(Optional.empty());
        when(provider.fetchDailyBars(eq("NEW"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(bar(NOW.minusSeconds(600), 100.0)));

        service.ingestDaily(List.of("NEW"), 5);

        ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(provider).fetchDailyBars(eq("NEW"), fromCaptor.capture(), eq(LocalDate.of(2026, 6, 10)));
        assertThat(fromCaptor.getValue()).isEqualTo(LocalDate.of(2021, 6, 10));
    }

    @Test
    void symbolWithTodaysBarIsNotRefetched() {
        when(repository.latestDailyTimestamp("AAPL")).thenReturn(Optional.of(LocalDate.of(2026, 6, 10)));

        IngestionResult result = service.ingestDaily(List.of("AAPL"), 5);

        assertThat(result.symbolsSucceeded()).isEqualTo(1);
        assertThat(result.barsAccepted()).isZero();
        verify(provider, never()).fetchDailyBars(eq("AAPL"), any(LocalDate.class), any(LocalDate.class));
    }

    @Test
    void universeSymbolsFallBackToStoredSymbolsWhenUniverseIsEmpty() {
        when(repository.listUniverseSymbols()).thenReturn(List.of());
        when(repository.listSymbols()).thenReturn(List.of("AAPL", "MSFT"));

        assertThat(service.universeSymbols()).containsExactly("AAPL", "MSFT");
    }

    @Test
    void universeSymbolsPreferTheExplicitUniverse() {
        when(repository.listUniverseSymbols()).thenReturn(List.of("AAPL"));

        assertThat(service.universeSymbols()).containsExactly("AAPL");
        verify(repository, never()).listSymbols();
    }

    @Test
    void registeringUniverseSymbolsDelegatesToTheRepository() {
        service.registerUniverseSymbols(List.of("AAPL"));

        verify(repository).registerUniverseSymbols(List.of("AAPL"), "universe");
    }

    private static Bar bar(Instant ts, double close) {
        return new Bar()
                .ts(OffsetDateTime.ofInstant(ts, ZoneOffset.UTC))
                .open(close - 0.5)
                .high(close + 0.5)
                .low(close - 1.0)
                .close(close)
                .volume(1_000L);
    }
}
