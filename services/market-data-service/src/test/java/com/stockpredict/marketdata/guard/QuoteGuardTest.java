package com.stockpredict.marketdata.guard;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockpredict.marketdata.config.MarketDataProperties;
import com.stockpredict.marketdata.provider.QuoteSnapshot;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class QuoteGuardTest {

    private final QuoteGuard guard = new QuoteGuard(new MarketDataProperties());
    private final Instant now = Instant.parse("2026-06-10T15:00:00Z");

    private QuoteSnapshot quote(double bid, double ask, double last) {
        return new QuoteSnapshot(bid, ask, last, now.atOffset(ZoneOffset.UTC));
    }

    @Test
    void acceptsAHealthyQuote() {
        assertThat(guard.isValid(quote(99.9, 100.1, 100.0))).isTrue();
        assertThat(guard.isValid(quote(100.0, 100.0, 100.0))).isTrue();
    }

    @Test
    void rejectsZeroNegativeOrNaNFields() {
        assertThat(guard.isValid(null)).isFalse();
        assertThat(guard.isValid(quote(0.0, 0.0, 0.0))).isFalse();
        assertThat(guard.isValid(quote(100.0, 100.1, 0.0))).isFalse();
        assertThat(guard.isValid(quote(-1.0, 100.1, 100.0))).isFalse();
        assertThat(guard.isValid(quote(Double.NaN, 100.1, 100.0))).isFalse();
    }

    @Test
    void rejectsCrossedOrAbsurdSpreads() {
        assertThat(guard.isValid(quote(101.0, 100.0, 100.5))).isFalse();
        assertThat(guard.isValid(quote(1.0, 100.0, 50.0))).isFalse();
    }
}
