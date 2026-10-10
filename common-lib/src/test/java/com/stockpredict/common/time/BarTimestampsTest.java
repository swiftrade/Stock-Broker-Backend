package com.stockpredict.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class BarTimestampsTest {

    @Test
    void dailyBarTimestampIsUtcMidnightOfTheSessionDate() {
        LocalDate session = LocalDate.of(2026, 3, 9); // Monday
        Instant ts = BarTimestamps.dailyBarTimestamp(session);
        assertThat(ts).isEqualTo(Instant.parse("2026-03-09T00:00:00Z"));
        assertThat(BarTimestamps.sessionDateOfDailyBar(ts)).isEqualTo(session);
    }

    @Test
    void utcMidnightWouldBeThePreviousDayInNewYork() {
        // Regression for the H1 off-by-one: 00:00Z on a session date is the
        // previous calendar day in ET, so ET-based date extraction is wrong.
        LocalDate session = LocalDate.of(2026, 3, 9); // Monday
        Instant ts = BarTimestamps.dailyBarTimestamp(session);
        assertThat(ts.atZone(TradingCalendar.MARKET_ZONE).toLocalDate())
                .isEqualTo(LocalDate.of(2026, 3, 8)); // Sunday
        assertThat(BarTimestamps.sessionDateOfDailyBar(ts)).isEqualTo(session);
    }

    @Test
    void dailyWindowEndIncludesTheAsOfBarAndExcludesTheNextOne() {
        LocalDate asOf = LocalDate.of(2026, 3, 9);
        Instant before = BarTimestamps.dailyWindowEnd(asOf);
        assertThat(before).isEqualTo(Instant.parse("2026-03-10T00:00:00Z"));
        assertThat(BarTimestamps.dailyBarTimestamp(asOf)).isBefore(before);
        // The bound is exclusive: the next session's bar sits exactly on it.
        assertThat(BarTimestamps.dailyBarTimestamp(asOf.plusDays(1))).isEqualTo(before);
        assertThat(BarTimestamps.dailyBarTimestamp(asOf.plusDays(2))).isAfter(before);
    }

    @Test
    void roundTripIsStableForEveryDayOfTheWeek() {
        LocalDate date = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 14; i++) {
            Instant ts = BarTimestamps.dailyBarTimestamp(date);
            assertThat(BarTimestamps.sessionDateOfDailyBar(ts)).isEqualTo(date);
            assertThat(ts.atOffset(ZoneOffset.UTC).toLocalDate()).isEqualTo(date);
            date = date.plusDays(1);
        }
    }
}
