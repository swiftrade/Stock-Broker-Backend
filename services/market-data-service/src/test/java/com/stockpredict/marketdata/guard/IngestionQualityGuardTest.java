package com.stockpredict.marketdata.guard;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockpredict.common.time.MarketClock;
import com.stockpredict.marketdata.api.model.Bar;
import com.stockpredict.marketdata.config.MarketDataProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class IngestionQualityGuardTest {

    private static final Instant NOW = Instant.parse("2026-06-10T15:00:00Z");

    private final MarketClock clock = new MarketClock(Clock.fixed(NOW, ZoneOffset.UTC));
    private final IngestionQualityGuard guard = new IngestionQualityGuard(new MarketDataProperties(), clock);

    @Test
    void rejectsNonPositivePrices() {
        GuardResult result = guard.validate(List.of(bar(NOW.minusSeconds(60), 0.0, 10.0, 0.0, 5.0)), "1d", false);

        assertThat(result.accepted()).isEmpty();
        assertThat(result.rejected()).hasSize(1);
    }

    @Test
    void rejectsHighBelowLow() {
        GuardResult result = guard.validate(List.of(bar(NOW.minusSeconds(60), 5.0, 4.0, 6.0, 5.0)), "1d", false);

        assertThat(result.rejected()).hasSize(1);
    }

    @Test
    void rejectsCloseOutsideRange() {
        GuardResult result = guard.validate(List.of(bar(NOW.minusSeconds(60), 5.0, 6.0, 4.0, 9.0)), "1d", false);

        assertThat(result.rejected()).hasSize(1);
    }

    @Test
    void rejectsFutureBars() {
        GuardResult result = guard.validate(List.of(bar(NOW.plusSeconds(600), 5.0, 6.0, 4.0, 5.0)), "1d", false);

        assertThat(result.rejected()).hasSize(1);
    }

    @Test
    void rejectsStaleIntradayBarsWhenLive() {
        Bar stale = bar(NOW.minusSeconds(2 * 3600), 5.0, 6.0, 4.0, 5.0);

        GuardResult live = guard.validate(List.of(stale), "5m", true);
        GuardResult backfill = guard.validate(List.of(stale), "5m", false);

        assertThat(live.rejected()).hasSize(1);
        assertThat(backfill.accepted()).hasSize(1);
    }

    @Test
    void flagsLargeDeviationButAcceptsTheBar() {
        Bar first = bar(NOW.minusSeconds(600), 100.0, 102.0, 99.0, 100.0);
        Bar second = bar(NOW.minusSeconds(300), 150.0, 210.0, 140.0, 200.0);

        GuardResult result = guard.validate(List.of(first, second), "1d", false);

        assertThat(result.accepted()).hasSize(2);
        assertThat(result.rejected()).isEmpty();
        assertThat(result.flagged()).containsExactly(second);
        assertThat(result.acceptedCount()).isEqualTo(2);
        assertThat(result.flaggedCount()).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateAndOutOfOrderTimestamps() {
        Bar first = bar(NOW.minusSeconds(600), 5.0, 6.0, 4.0, 5.0);
        Bar duplicate = bar(NOW.minusSeconds(600), 5.0, 6.0, 4.0, 5.0);
        Bar outOfOrder = bar(NOW.minusSeconds(900), 5.0, 6.0, 4.0, 5.0);

        GuardResult result = guard.validate(List.of(first, duplicate, outOfOrder), "1d", false);

        assertThat(result.accepted()).containsExactly(first);
        assertThat(result.rejected()).containsExactly(duplicate, outOfOrder);
    }

    @Test
    void acceptsFreshSaneBars() {
        GuardResult result = guard.validate(List.of(bar(NOW.minusSeconds(60), 5.0, 6.0, 4.0, 5.5)), "1d", false);

        assertThat(result.accepted()).hasSize(1);
        assertThat(result.rejected()).isEmpty();
        assertThat(result.flagged()).isEmpty();
    }

    private static Bar bar(Instant ts, double open, double high, double low, double close) {
        return new Bar()
                .ts(OffsetDateTime.ofInstant(ts, ZoneOffset.UTC))
                .open(open)
                .high(high)
                .low(low)
                .close(close)
                .volume(1_000L);
    }
}
