package com.stockpredict.marketdata.provider;

import com.stockpredict.common.time.BarTimestamps;
import com.stockpredict.common.time.MarketClock;
import com.stockpredict.common.time.TradingCalendar;
import com.stockpredict.marketdata.api.model.Bar;
import com.stockpredict.marketdata.api.model.SymbolMatch;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;

/**
 * Deterministic provider used when no Polygon API key is configured. Prices are
 * derived from a hash of (symbol, date) so every process/test sees the same
 * series for the same inputs.
 */
public class SyntheticProvider implements MarketDataProvider {

    private static final String SOURCE = "synthetic";
    private static final LocalTime SESSION_OPEN = LocalTime.of(9, 30);
    private static final int DAILY_BAR_SLOTS = 78;

    private static final List<SymbolEntry> SYMBOLS = List.of(
            new SymbolEntry("AAPL", "Apple Inc.", "XNAS"),
            new SymbolEntry("MSFT", "Microsoft Corporation", "XNAS"),
            new SymbolEntry("NVDA", "NVIDIA Corporation", "XNAS"),
            new SymbolEntry("AMZN", "Amazon.com Inc.", "XNAS"),
            new SymbolEntry("GOOGL", "Alphabet Inc. Class A", "XNAS"),
            new SymbolEntry("META", "Meta Platforms Inc.", "XNAS"),
            new SymbolEntry("TSLA", "Tesla Inc.", "XNAS"),
            new SymbolEntry("BRK.B", "Berkshire Hathaway Inc. Class B", "XNYS"),
            new SymbolEntry("JPM", "JPMorgan Chase & Co.", "XNYS"),
            new SymbolEntry("V", "Visa Inc.", "XNYS"),
            new SymbolEntry("UNH", "UnitedHealth Group Inc.", "XNYS"),
            new SymbolEntry("XOM", "Exxon Mobil Corporation", "XNYS"),
            new SymbolEntry("LLY", "Eli Lilly and Company", "XNYS"),
            new SymbolEntry("AVGO", "Broadcom Inc.", "XNAS"),
            new SymbolEntry("JNJ", "Johnson & Johnson", "XNYS"),
            new SymbolEntry("WMT", "Walmart Inc.", "XNYS"),
            new SymbolEntry("MA", "Mastercard Inc.", "XNYS"),
            new SymbolEntry("PG", "Procter & Gamble Company", "XNYS"),
            new SymbolEntry("HD", "Home Depot Inc.", "XNYS"),
            new SymbolEntry("COST", "Costco Wholesale Corporation", "XNAS"),
            new SymbolEntry("ORCL", "Oracle Corporation", "XNYS"),
            new SymbolEntry("AMD", "Advanced Micro Devices Inc.", "XNAS"),
            new SymbolEntry("NFLX", "Netflix Inc.", "XNAS"),
            new SymbolEntry("CRM", "Salesforce Inc.", "XNYS"),
            new SymbolEntry("ADBE", "Adobe Inc.", "XNAS"),
            new SymbolEntry("INTC", "Intel Corporation", "XNAS"),
            new SymbolEntry("KO", "Coca-Cola Company", "XNYS"),
            new SymbolEntry("PEP", "PepsiCo Inc.", "XNAS"),
            new SymbolEntry("DIS", "Walt Disney Company", "XNYS"),
            new SymbolEntry("BAC", "Bank of America Corporation", "XNYS"),
            new SymbolEntry("SPY", "SPDR S&P 500 ETF Trust", "ARCX"),
            new SymbolEntry("QQQ", "Invesco QQQ Trust", "XNAS"),
            new SymbolEntry("IWM", "iShares Russell 2000 ETF", "ARCX"),
            new SymbolEntry("XLK", "Technology Select Sector SPDR", "ARCX"),
            new SymbolEntry("XLF", "Financial Select Sector SPDR", "ARCX"),
            new SymbolEntry("XLE", "Energy Select Sector SPDR", "ARCX"),
            new SymbolEntry("XLI", "Industrial Select Sector SPDR", "ARCX"),
            new SymbolEntry("XLP", "Consumer Staples Select Sector SPDR", "ARCX"),
            new SymbolEntry("XLU", "Utilities Select Sector SPDR", "ARCX"),
            new SymbolEntry("XLV", "Health Care Select Sector SPDR", "ARCX"),
            new SymbolEntry("XLY", "Consumer Discretionary Select Sector SPDR", "ARCX"),
            new SymbolEntry("XLB", "Materials Select Sector SPDR", "ARCX"),
            new SymbolEntry("XLRE", "Real Estate Select Sector SPDR", "ARCX"),
            new SymbolEntry("XLC", "Communication Services Select Sector SPDR", "ARCX"));

    private final MarketClock clock;
    private final TradingCalendar calendar;

    public SyntheticProvider(MarketClock clock, TradingCalendar calendar) {
        this.clock = clock;
        this.calendar = calendar;
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public List<Bar> fetchDailyBars(String symbol, LocalDate from, LocalDate to) {
        List<Bar> bars = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (calendar.isTradingDay(date)) {
                bars.add(dailyBar(symbol, date));
            }
        }
        return bars;
    }

    @Override
    public List<Bar> fetchIntradayBars(String symbol, LocalDate from, LocalDate to) {
        List<Bar> bars = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (calendar.isTradingDay(date)) {
                bars.addAll(intradayBars(symbol, date));
            }
        }
        return bars;
    }

    private List<Bar> intradayBars(String symbol, LocalDate date) {
        LocalTime sessionClose = calendar.isEarlyClose(date) ? LocalTime.of(13, 0) : LocalTime.of(16, 0);
        int slots = (int) (Duration.between(SESSION_OPEN, sessionClose).toMinutes() / 5);
        double previousClose = close(symbol, calendar.previousTradingDay(date));
        double dayClose = close(symbol, date);
        List<Bar> bars = new ArrayList<>(slots);
        double previous = previousClose;
        for (int i = 0; i < slots; i++) {
            ZonedDateTime et = date.atTime(SESSION_OPEN.plusMinutes(5L * i)).atZone(TradingCalendar.MARKET_ZONE);
            Random rnd = rng(symbol, date.toEpochDay(), i + 17L);
            double target = previousClose + (dayClose - previousClose) * ((i + 1.0) / slots);
            double closePrice = Math.max(0.5, target * (1 + 0.002 * rnd.nextGaussian()));
            double open = previous;
            double high = Math.max(open, closePrice) * (1 + 0.0005 + 0.0015 * rnd.nextDouble());
            double low = Math.min(open, closePrice) * (1 - 0.0005 - 0.0015 * rnd.nextDouble());
            long volume = 5_000 + (long) (rnd.nextDouble() * 45_000);
            bars.add(new Bar()
                    .ts(et.toOffsetDateTime())
                    .open(round(open))
                    .high(round(high))
                    .low(round(low))
                    .close(round(closePrice))
                    .volume(volume)
                    .vwap(round((open + high + low + closePrice) / 4.0))
                    .tradeCount(volume / 100));
            previous = closePrice;
        }
        return bars;
    }

    private Bar dailyBar(String symbol, LocalDate date) {
        double close = close(symbol, date);
        double previousClose = close(symbol, calendar.previousTradingDay(date));
        Random rnd = rng(symbol, date.toEpochDay(), 1L);
        double open = previousClose * (1 + 0.003 * rnd.nextGaussian());
        double high = Math.max(open, close) * (1 + 0.004 + 0.006 * rnd.nextDouble());
        double low = Math.min(open, close) * (1 - 0.004 - 0.006 * rnd.nextDouble());
        long volume = 500_000 + (long) (rnd.nextDouble() * 9_500_000);
        return new Bar()
                .ts(BarTimestamps.dailyBarTimestamp(date).atOffset(ZoneOffset.UTC))
                .open(round(open))
                .high(round(high))
                .low(round(low))
                .close(round(close))
                .volume(volume)
                .vwap(round((open + high + low + close) / 4.0))
                .tradeCount(volume / 100);
    }

    @Override
    public Optional<QuoteSnapshot> fetchQuote(String symbol) {
        LocalDate today = clock.todayEt();
        double last = close(symbol, today);
        double spread = Math.max(0.01, last * 0.0005);
        return Optional.of(new QuoteSnapshot(
                round(last - spread),
                round(last + spread),
                round(last),
                clock.now().atOffset(ZoneOffset.UTC)));
    }

    @Override
    public List<SymbolMatch> search(String query, int limit) {
        String needle = query == null ? "" : query.trim().toUpperCase(Locale.ROOT);
        return SYMBOLS.stream()
                .filter(entry -> entry.symbol().contains(needle) || entry.name().toUpperCase(Locale.ROOT).contains(needle))
                .limit(Math.max(0, limit))
                .map(entry -> new SymbolMatch()
                        .symbol(entry.symbol())
                        .name(entry.name())
                        .exchange(entry.exchange()))
                .toList();
    }

    /** Deterministic pseudo-close for a symbol/date pair (always positive). */
    static double close(String symbol, LocalDate date) {
        long day = date.toEpochDay();
        Random rnd = rng(symbol, day, 0L);
        double base = 25.0 + Math.floorMod(symbol.hashCode(), 475);
        double phase = Math.floorMod(symbol.hashCode(), 97) / 7.0;
        double trend = 1.0 + Math.floorMod(symbol.hashCode(), 9) / 10_000.0;
        double wave = 0.04 * Math.sin(day / 23.0 + phase);
        double noise = 0.015 * rnd.nextGaussian();
        return Math.max(1.0, base * Math.pow(trend, day - 20_000L) * Math.exp(wave + noise));
    }

    private static Random rng(String symbol, long day, long salt) {
        long hash = symbol.hashCode() * 1_125_899_906_842_597L;
        hash ^= day * 0x9E3779B97F4A7C15L;
        hash ^= salt * 0xBF58476D1CE4E5B9L;
        return new Random(hash);
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private record SymbolEntry(String symbol, String name, String exchange) {
    }
}
