package com.stockpredict.prediction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpredict.common.plan.Plan;
import com.stockpredict.common.time.MarketClock;
import com.stockpredict.common.time.TradingCalendar;
import com.stockpredict.prediction.config.PredictionProperties;
import com.stockpredict.prediction.data.PredictionRepository;
import com.stockpredict.prediction.data.PredictionRow;
import com.stockpredict.prediction.model.ModelRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PredictionQueryServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 10); // Wednesday
    private static final LocalDate LAST_SESSION = LocalDate.of(2026, 6, 9);

    private PredictionRepository repository;
    private ModelRegistry registry;
    private PredictionQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(PredictionRepository.class);
        registry = mock(ModelRegistry.class);
        when(registry.activeVersion()).thenReturn(Optional.of("test-1.0.0"));
        PredictionProperties properties = new PredictionProperties();
        properties.setFreePlanLimit(20);
        properties.setDisclaimer("Research signal only.");
        MarketClock clock = new MarketClock(Clock.fixed(
                Instant.parse("2026-06-10T15:00:00Z"), ZoneOffset.UTC));
        service = new PredictionQueryService(repository, registry, properties,
                new TradingCalendar(), clock, new ObjectMapper());
    }

    private static PredictionRow row(String symbol, double predictedReturn) {
        return new PredictionRow(symbol, LAST_SESSION, 1, predictedReturn, 0.6, 0.6,
                100.0, 100.0 * Math.exp(predictedReturn), "{\"log_return_1d\": 0.01}", "test-1.0.0");
    }

    @Test
    void freePlanIsCappedAtTheConfiguredLimit() {
        when(repository.latestAsOf(1)).thenReturn(Optional.of(LAST_SESSION));
        when(repository.find(eq(LAST_SESSION), eq(1), any(), anyInt()))
                .thenReturn(List.of(row("AAA", 0.01), row("BBB", 0.02)));

        var result = service.getIntradayPredictions(null, 500, "predicted_return_desc", Plan.FREE);

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(repository).find(eq(LAST_SESSION), eq(1), any(), limit.capture());
        assertThat(limit.getValue()).isEqualTo(20);
        assertThat(result.getPredictions()).hasSize(2);
        assertThat(result.getPredictions().get(0).getRank()).isEqualTo(1);
        assertThat(result.getPredictions().get(1).getRank()).isEqualTo(2);
        assertThat(result.getStale()).isFalse();
        assertThat(result.getModelVersion()).isEqualTo("test-1.0.0");
    }

    @Test
    void premiumPlanKeepsRequestedLimit() {
        when(repository.latestAsOf(1)).thenReturn(Optional.of(LAST_SESSION));
        when(repository.find(eq(LAST_SESSION), eq(1), any(), anyInt())).thenReturn(List.of());

        service.getIntradayPredictions(null, 300, "symbol_asc", Plan.PREMIUM);

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(repository).find(eq(LAST_SESSION), eq(1), any(), limit.capture());
        assertThat(limit.getValue()).isEqualTo(300);
    }

    @Test
    void oldAsOfIsFlaggedStale() {
        LocalDate old = LAST_SESSION.minusDays(3);
        when(repository.latestAsOf(1)).thenReturn(Optional.of(old));
        when(repository.find(eq(old), eq(1), any(), anyInt()))
                .thenReturn(List.of(new PredictionRow("AAA", old, 1, 0.01, null, null,
                        100.0, 101.0, null, "test-1.0.0")));

        var result = service.getIntradayPredictions(null, 10, null, Plan.PREMIUM);

        assertThat(result.getStale()).isTrue();
    }

    @Test
    void noPredictionsReturnsStaleEmptyList() {
        when(repository.latestAsOf(1)).thenReturn(Optional.empty());

        var result = service.getIntradayPredictions(null, 10, null, Plan.PREMIUM);

        assertThat(result.getPredictions()).isEmpty();
        assertThat(result.getStale()).isTrue();
        assertThat(result.getAsOf()).isNull();
        assertThat(result.getModelVersion()).isEqualTo("test-1.0.0");
    }

    @Test
    void symbolPredictionMapsAllHorizonsAndFeatures() {
        when(repository.latestAsOfForSymbol("AAA")).thenReturn(Optional.of(LAST_SESSION));
        when(repository.findBySymbolAtAsOf("AAA", LAST_SESSION)).thenReturn(List.of(
                new PredictionRow("AAA", LAST_SESSION, 1, 0.01, null, null, 100.0, 101.0,
                        "{\"log_return_1d\": 0.01}", "test-1.0.0"),
                new PredictionRow("AAA", LAST_SESSION, 20, 0.05, null, null, 100.0, 105.1,
                        "{\"log_return_1d\": 0.01}", "test-1.0.0")));

        var result = service.getSymbolPrediction("AAA");

        assertThat(result.getHorizons()).hasSize(2);
        assertThat(result.getHorizons().get(0).getHorizonDays()).isEqualTo(1);
        assertThat(result.getHorizons().get(1).getHorizonDays()).isEqualTo(20);
        assertThat(result.getFeatures()).containsKey("log_return_1d");
        assertThat(result.getSymbol()).isEqualTo("AAA");
    }
}
