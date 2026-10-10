package com.stockpredict.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpredict.common.time.MarketClock;
import com.stockpredict.common.time.TradingCalendar;
import com.stockpredict.ranking.config.RankingProperties;
import com.stockpredict.ranking.data.RankingRepository;
import com.stockpredict.ranking.data.RankingRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RankingQueryServiceTest {

    private static final LocalDate RUN_DATE = LocalDate.of(2026, 6, 9);

    private RankingRepository repository;
    private RankingQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(RankingRepository.class);
        RankingProperties properties = new RankingProperties();
        properties.setDisclaimer("Research signal only.");
        MarketClock clock = new MarketClock(Clock.fixed(
                Instant.parse("2026-06-10T15:00:00Z"), ZoneOffset.UTC));
        service = new RankingQueryService(repository, properties, new ObjectMapper(),
                new TradingCalendar(), clock);
    }

    private static RankingRow row(String symbol, int rank, Integer rankChange) {
        return new RankingRow(RUN_DATE, "test-1.0.0", symbol, rank, rankChange,
                0.05 - rank * 0.001, 0.20, "Tech", 0.1,
                "{\"log_return_20d\": 0.03}");
    }

    @Test
    void latestRunIsReturnedWithRationale() {
        when(repository.latestRunDate()).thenReturn(Optional.of(RUN_DATE));
        when(repository.findByRunDate(eq(RUN_DATE), anyInt()))
                .thenReturn(List.of(row("AAA", 1, 1), row("BBB", 2, null)));

        var ranking = service.getLongTermRecommendations(null, null);

        assertThat(ranking.getRunDate()).isEqualTo(RUN_DATE);
        assertThat(ranking.getModelVersion()).isEqualTo("test-1.0.0");
        assertThat(ranking.getPicks()).hasSize(2);
        assertThat(ranking.getPicks().get(0).getSymbol()).isEqualTo("AAA");
        assertThat(ranking.getPicks().get(0).getRankChange()).isEqualTo(1);
        assertThat(ranking.getPicks().get(0).getRationale()).containsKey("log_return_20d");
        assertThat(ranking.getDisclaimer()).isEqualTo("Research signal only.");
        assertThat(ranking.getStale()).isFalse();
    }

    @Test
    void explicitDateIsHonoured() {
        LocalDate past = LocalDate.of(2026, 5, 1);
        when(repository.findByRunDate(eq(past), anyInt())).thenReturn(List.of(row("AAA", 1, 0)));

        var ranking = service.getLongTermRecommendations(10, past);

        assertThat(ranking.getRunDate()).isEqualTo(past);
        assertThat(ranking.getPicks()).hasSize(1);
        assertThat(ranking.getStale()).isTrue();
    }

    @Test
    void emptyHistoryReturnsNoPicks() {
        when(repository.latestRunDate()).thenReturn(Optional.empty());

        var ranking = service.getLongTermRecommendations(null, null);

        assertThat(ranking.getPicks()).isEmpty();
        assertThat(ranking.getModelVersion()).isEqualTo("unknown");
    }
}
