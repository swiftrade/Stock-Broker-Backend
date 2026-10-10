package com.stockpredict.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * Injectable time source. Tests override the underlying {@link Clock} instead of
 * hard-coding dates, and all market-hours decisions go through it.
 */
public class MarketClock {

    private final Clock clock;

    public MarketClock() {
        this(Clock.systemUTC());
    }

    public MarketClock(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }

    public LocalDate todayEt() {
        return LocalDate.now(clock.withZone(TradingCalendar.MARKET_ZONE));
    }

    public ZonedDateTime nowEt() {
        return ZonedDateTime.now(clock.withZone(TradingCalendar.MARKET_ZONE));
    }

    public Clock clock() {
        return clock;
    }
}
