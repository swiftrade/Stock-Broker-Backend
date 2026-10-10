package com.stockpredict.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class TradingCalendarTest {

    private final TradingCalendar calendar = new TradingCalendar();

    @Test
    void weekendIsNotATradingDay() {
        assertThat(calendar.isTradingDay(LocalDate.of(2026, 1, 3))).isFalse(); // Saturday
        assertThat(calendar.isTradingDay(LocalDate.of(2026, 1, 4))).isFalse(); // Sunday
    }

    @Test
    void regularWeekdayIsATradingDay() {
        assertThat(calendar.isTradingDay(LocalDate.of(2026, 1, 2))).isTrue();
    }

    @Test
    void newYearRules() {
        assertThat(calendar.isTradingDay(LocalDate.of(2025, 1, 1))).isFalse();
        assertThat(calendar.isTradingDay(LocalDate.of(2026, 1, 1))).isFalse();
        // Jan 1 2022 was a Saturday: NYSE was open on Dec 31 2021.
        assertThat(calendar.isTradingDay(LocalDate.of(2021, 12, 31))).isTrue();
        // Jan 1 2023 was a Sunday: observed Monday Jan 2.
        assertThat(calendar.isTradingDay(LocalDate.of(2023, 1, 2))).isFalse();
    }

    @Test
    void fixedHolidays2026() {
        assertThat(calendar.isTradingDay(LocalDate.of(2026, 6, 19))).isFalse(); // Juneteenth (Fri)
        assertThat(calendar.isTradingDay(LocalDate.of(2026, 7, 3))).isFalse();  // July 4 observed (Sat)
        assertThat(calendar.isTradingDay(LocalDate.of(2026, 12, 25))).isFalse();
    }

    @Test
    void movingHolidays2026() {
        assertThat(calendar.isTradingDay(LocalDate.of(2026, 1, 19))).isFalse(); // MLK
        assertThat(calendar.isTradingDay(LocalDate.of(2026, 2, 16))).isFalse(); // Presidents
        assertThat(calendar.isTradingDay(LocalDate.of(2026, 4, 3))).isFalse();  // Good Friday
        assertThat(calendar.isTradingDay(LocalDate.of(2026, 5, 25))).isFalse(); // Memorial
        assertThat(calendar.isTradingDay(LocalDate.of(2026, 9, 7))).isFalse();  // Labor
        assertThat(calendar.isTradingDay(LocalDate.of(2026, 11, 26))).isFalse(); // Thanksgiving
    }

    @Test
    void goodFriday2025() {
        assertThat(calendar.isTradingDay(LocalDate.of(2025, 4, 18))).isFalse();
    }

    @Test
    void earlyCloses() {
        assertThat(calendar.isEarlyClose(LocalDate.of(2025, 11, 28))).isTrue(); // day after Thanksgiving
        assertThat(calendar.isEarlyClose(LocalDate.of(2025, 7, 3))).isTrue();   // July 3 (Jul 4 Fri)
        assertThat(calendar.isEarlyClose(LocalDate.of(2025, 12, 24))).isTrue(); // Christmas Eve (Thu)
        assertThat(calendar.isEarlyClose(LocalDate.of(2026, 12, 24))).isTrue(); // Christmas 2026 is a Friday
        assertThat(calendar.isEarlyClose(LocalDate.of(2027, 12, 24))).isFalse(); // observed Christmas holiday
        assertThat(calendar.isEarlyClose(LocalDate.of(2026, 6, 10))).isFalse();
    }

    @Test
    void nextAndPreviousTradingDaySkipHolidayWeekend() {
        assertThat(calendar.nextTradingDay(LocalDate.of(2026, 7, 2)))
                .isEqualTo(LocalDate.of(2026, 7, 6));
        assertThat(calendar.previousTradingDay(LocalDate.of(2026, 7, 6)))
                .isEqualTo(LocalDate.of(2026, 7, 2));
    }

    @Test
    void marketHours() {
        // 14:00 UTC = 10:00 ET in June (EDT)
        assertThat(calendar.isMarketOpen(Instant.parse("2026-06-10T14:00:00Z"))).isTrue();
        // 21:00 UTC = 17:00 ET
        assertThat(calendar.isMarketOpen(Instant.parse("2026-06-10T21:00:00Z"))).isFalse();
        // 13:00 UTC = 09:00 ET (pre-open)
        assertThat(calendar.isMarketOpen(Instant.parse("2026-06-10T13:00:00Z"))).isFalse();
        // weekend
        assertThat(calendar.isMarketOpen(Instant.parse("2026-06-13T15:00:00Z"))).isFalse();
    }

    @Test
    void earlyCloseMarketHours() {
        // 2025-11-28 (early close): 18:30 UTC = 13:30 ET -> closed
        assertThat(calendar.isMarketOpen(Instant.parse("2025-11-28T18:30:00Z"))).isFalse();
        // 17:00 UTC = 12:00 ET -> open
        assertThat(calendar.isMarketOpen(Instant.parse("2025-11-28T17:00:00Z"))).isTrue();
    }

    @Test
    void extraClosuresAreHonoured() {
        TradingCalendar withClosure = new TradingCalendar(java.util.Set.of(LocalDate.of(2026, 1, 2)));
        assertThat(withClosure.isTradingDay(LocalDate.of(2026, 1, 2))).isFalse();
    }
}
