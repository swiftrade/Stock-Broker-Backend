package com.stockpredict.common.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * The single source of truth for how daily-bar timestamps map to session dates.
 *
 * <p>Storage convention: a daily bar for session date {@code D} is stored at
 * {@code D 00:00Z} (UTC midnight). This is what Polygon day aggregates use and
 * what the synthetic provider emits. Because {@code D 00:00Z} is the previous
 * calendar day in New York, consumers must not interpret daily-bar timestamps
 * in {@link TradingCalendar#MARKET_ZONE}; use {@link #sessionDateOfDailyBar}
 * instead. Intraday bars ({@code bars_5m}) keep their real ET session instant
 * and are interpreted with {@link TradingCalendar#MARKET_ZONE}.
 */
public final class BarTimestamps {

    private BarTimestamps() {
    }

    /** Canonical storage timestamp for the daily bar of {@code sessionDate}. */
    public static Instant dailyBarTimestamp(LocalDate sessionDate) {
        return sessionDate.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Session date of a daily bar stored at UTC midnight (or any instant of that UTC date). */
    public static LocalDate sessionDateOfDailyBar(Instant timestamp) {
        return timestamp.atZone(ZoneOffset.UTC).toLocalDate();
    }

    /**
     * Exclusive upper bound for a trailing window that ends at (and includes)
     * the daily bar of {@code asOf}: {@code (asOf + 1) 00:00Z}.
     */
    public static Instant dailyWindowEnd(LocalDate asOf) {
        return dailyBarTimestamp(asOf.plusDays(1));
    }
}
