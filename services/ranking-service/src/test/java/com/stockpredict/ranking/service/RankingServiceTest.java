package com.stockpredict.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpredict.common.error.ApiException;
import com.stockpredict.common.time.MarketClock;
import com.stockpredict.ranking.config.RankingProperties;
import com.stockpredict.ranking.data.PredictionSource;
import com.stockpredict.ranking.data.PredictionSource.PredictionSnapshot;
import com.stockpredict.ranking.data.RankingRepository;
import com.stockpredict.ranking.data.RankingRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RankingServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 10);

    private PredictionSource predictionSource;
    private RankingRepository rankingRepository;
    private RankingProperties properties;
    private RankingService service;

    @BeforeEach
    void setUp() {
        predictionSource = mock(PredictionSource.class);
        rankingRepository = mock(RankingRepository.class);
        properties = new RankingProperties();
        MarketClock clock = new MarketClock(Clock.fixed(
                Instant.parse("2026-06-10T12:00:00Z"), ZoneOffset.UTC));
        SectorProvider sectorProvider = new SectorProvider(Map.of(
                "AAA", "Tech", "BBB", "Health", "CCC", "Energy"));
        service = new RankingService(predictionSource, rankingRepository, sectorProvider,
                properties, clock, new ObjectMapper());
    }

    private static PredictionSnapshot snapshot(String symbol, double predicted) {
        return new PredictionSnapshot(symbol, predicted, "test-1.0.0",
                "{\"log_return_20d\": 0.03, \"volatility_20d\": 0.2, \"rsi_14\": 61.5}");
    }

    @Test
    void runSizesPicksAndComputesRankChanges() {
        when(predictionSource.topByPredictedReturn(20, 50)).thenReturn(List.of(
                snapshot("AAA", 0.05), snapshot("BBB", 0.04), snapshot("CCC", 0.03)));
        when(predictionSource.latestAsOf(20)).thenReturn(Optional.of(TODAY.minusDays(1)));
        when(predictionSource.realizedVolatility(anyCollection(), any(), anyInt()))
                .thenReturn(Map.of("AAA", 0.20, "BBB", 0.25, "CCC", 0.30));
        when(rankingRepository.previousRanks(TODAY)).thenReturn(Map.of("AAA", 2, "CCC", 3));

        RankingResult result = service.run();

        assertThat(result.runDate()).isEqualTo(TODAY);
        assertThat(result.modelVersion()).isEqualTo("test-1.0.0");
        assertThat(result.pickCount()).isEqualTo(3);

        ArgumentCaptor<List<RankingRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(rankingRepository).upsertAll(captor.capture());
        List<RankingRow> rows = captor.getValue();
        assertThat(rows).extracting(RankingRow::symbol).containsExactly("AAA", "BBB", "CCC");
        assertThat(rows.get(0).rank()).isEqualTo(1);
        assertThat(rows.get(0).rankChange()).isEqualTo(1); // was 2, now 1
        assertThat(rows.get(1).rankChange()).isNull();     // new entry
        assertThat(rows.get(2).rankChange()).isEqualTo(0);
        assertThat(rows.stream().mapToDouble(RankingRow::weight).sum()).isCloseTo(1.0, within(1e-9));
        assertThat(rows.get(0).rationaleJson()).contains("log_return_20d");
        assertThat(rows.get(0).sector()).isEqualTo("Tech");
    }

    @Test
    void lowerVolatilityGetsHigherWeight() {
        // Sector cap off: with only two names a 25% sector cap would dominate.
        properties.setMaxSectorWeight(1.0);
        when(predictionSource.topByPredictedReturn(20, 50)).thenReturn(List.of(
                snapshot("AAA", 0.05), snapshot("BBB", 0.04)));
        when(predictionSource.latestAsOf(20)).thenReturn(Optional.of(TODAY));
        when(predictionSource.realizedVolatility(anyCollection(), any(), anyInt()))
                .thenReturn(Map.of("AAA", 0.10, "BBB", 0.40));
        when(rankingRepository.previousRanks(TODAY)).thenReturn(Map.of());

        service.run();

        ArgumentCaptor<List<RankingRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(rankingRepository).upsertAll(captor.capture());
        assertThat(captor.getValue().get(0).weight())
                .isGreaterThan(captor.getValue().get(1).weight());
    }

    @Test
    void noPredictionsFailsFast() {
        when(predictionSource.topByPredictedReturn(anyInt(), anyInt())).thenReturn(List.of());

        assertThatThrownBy(() -> service.run())
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No 20d predictions");
    }
}
