package com.stockpredict.common.time;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NYSE trading calendar computed from the published holiday rules (no external
 * data source). Supports regular and early-close sessions; ad-hoc closures
 * (e.g. national days of mourning) can be supplied at construction.
 */
public class TradingCalendar {

    public static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
    public static final LocalTime OPEN_TIME = LocalTime.of(9, 30);
    public static final LocalTime CLOSE_TIME = LocalTime.of(16, 0);
    public static final LocalTime EARLY_CLOSE_TIME = LocalTime.of(13, 0);

    private final Set<LocalDate> extraClosures;
    private final Map<Integer, Set<LocalDate>> holidaysByYear = new ConcurrentHashMap<>();

    public TradingCalendar() {
        this(Set.of());
    }

    public TradingCalendar(Set<LocalDate> extraClosures) {
        this.extraClosures = extraClosures == null ? Set.of() : Set.copyOf(extraClosures);
    }

    public boolean isTradingDay(LocalDate date) {
        if (date == null) {
            return false;
        }
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return false;
        }
        if (extraClosures.contains(date)) {
            return false;
        }
        return !holidays(date.getYear()).contains(date);
    }

    public Set<LocalDate> holidays(int year) {
        return holidaysByYear.computeIfAbsent(year, TradingCalendar::computeHolidays);
    }

    public boolean isEarlyClose(LocalDate date) {
        if (!isTradingDay(date)) {
            return false;
        }
        int year = date.getYear();
        return date.equals(dayAfterThanksgiving(year))
                || date.equals(LocalDate.of(year, Month.JULY, 3))
                || date.equals(LocalDate.of(year, Month.DECEMBER, 24));
    }

    public ZonedDateTime openTime(LocalDate date) {
        return date.atTime(OPEN_TIME).atZone(MARKET_ZONE);
    }

    public ZonedDateTime closeTime(LocalDate date) {
        LocalTime close = isEarlyClose(date) ? EARLY_CLOSE_TIME : CLOSE_TIME;
        return date.atTime(close).atZone(MARKET_ZONE);
    }

    public boolean isMarketOpen(Instant instant) {
        ZonedDateTime et = instant.atZone(MARKET_ZONE);
        LocalDate date = et.toLocalDate();
        if (!isTradingDay(date)) {
            return false;
        }
        LocalTime time = et.toLocalTime();
        LocalTime close = isEarlyClose(date) ? EARLY_CLOSE_TIME : CLOSE_TIME;
        return !time.isBefore(OPEN_TIME) && time.isBefore(close);
    }

    public LocalDate nextTradingDay(LocalDate date) {
        LocalDate next = date.plusDays(1);
        while (!isTradingDay(next)) {
            next = next.plusDays(1);
        }
        return next;
    }

    public LocalDate previousTradingDay(LocalDate date) {
        LocalDate previous = date.minusDays(1);
        while (!isTradingDay(previous)) {
            previous = previous.minusDays(1);
        }
        return previous;
    }

    public List<LocalDate> tradingDaysBetween(LocalDate fromInclusive, LocalDate toInclusive) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate date = fromInclusive; !date.isAfter(toInclusive); date = date.plusDays(1)) {
            if (isTradingDay(date)) {
                days.add(date);
            }
        }
        return days;
    }

    static Set<LocalDate> computeHolidays(int year) {
        Set<LocalDate> holidays = new HashSet<>();

        // New Year's Day: Sunday -> Monday; Saturday -> not observed (NYSE rule).
        LocalDate newYear = LocalDate.of(year, Month.JANUARY, 1);
        if (newYear.getDayOfWeek() == DayOfWeek.SUNDAY) {
            holidays.add(newYear.plusDays(1));
        } else if (newYear.getDayOfWeek() != DayOfWeek.SATURDAY) {
            holidays.add(newYear);
        }

        holidays.add(nthWeekday(year, Month.JANUARY, DayOfWeek.MONDAY, 3));   // MLK Day
        holidays.add(nthWeekday(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3)); // Washington's Birthday
        holidays.add(easterSunday(year).minusDays(2));                       // Good Friday
        holidays.add(lastWeekday(year, Month.MAY, DayOfWeek.MONDAY));        // Memorial Day
        if (year >= 2022) {
            holidays.add(observed(LocalDate.of(year, Month.JUNE, 19)));      // Juneteenth
        }
        holidays.add(observed(LocalDate.of(year, Month.JULY, 4)));           // Independence Day
        holidays.add(nthWeekday(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1)); // Labor Day
        holidays.add(nthWeekday(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4)); // Thanksgiving
        holidays.add(observed(LocalDate.of(year, Month.DECEMBER, 25)));      // Christmas

        holidays.removeIf(date -> date.getDayOfWeek() == DayOfWeek.SATURDAY
                || date.getDayOfWeek() == DayOfWeek.SUNDAY);
        return Set.copyOf(holidays);
    }

    private static LocalDate dayAfterThanksgiving(int year) {
        return nthWeekday(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4).plusDays(1);
    }

    private static LocalDate observed(LocalDate date) {
        return switch (date.getDayOfWeek()) {
            case SATURDAY -> date.minusDays(1);
            case SUNDAY -> date.plusDays(1);
            default -> date;
        };
    }

    private static LocalDate nthWeekday(int year, Month month, DayOfWeek dayOfWeek, int n) {
        LocalDate date = LocalDate.of(year, month, 1);
        while (date.getDayOfWeek() != dayOfWeek) {
            date = date.plusDays(1);
        }
        return date.plusWeeks(n - 1L);
    }

    private static LocalDate lastWeekday(int year, Month month, DayOfWeek dayOfWeek) {
        LocalDate date = LocalDate.of(year, month, month.length(java.time.Year.isLeap(year)));
        while (date.getDayOfWeek() != dayOfWeek) {
            date = date.minusDays(1);
        }
        return date;
    }

    /** Anonymous Gregorian (Meeus/Jones/Butcher) Easter algorithm. */
    static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }
}
