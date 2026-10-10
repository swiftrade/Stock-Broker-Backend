package com.stockpredict.marketdata.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockpredict.common.time.MarketClock;
import com.stockpredict.common.time.TradingCalendar;
import com.stockpredict.marketdata.api.model.Bar;
import com.stockpredict.marketdata.api.model.SymbolMatch;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SyntheticProviderTest {

    private static final Instant NOW = Instant.parse("2026-06-10T15:00:00Z");
    private static final LocalDate MONDAY = LocalDate.of(2026, 6, 1);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 6, 5);

    private final MarketClock clock = new MarketClock(Clock.fixed(NOW, ZoneOffset.UTC));
    private final TradingCalendar calendar = new TradingCalendar();
    private final SyntheticProvider provider = new SyntheticProvider(clock, calendar);

    @Test
    void dailyBarsAreDeterministicAndOrdered() {
        List<Bar> first = provider.fetchDailyBars("AAPL", MONDAY, FRIDAY);
        List<Bar> second = provider.fetchDailyBars("AAPL", MONDAY, FRIDAY);

        assertThat(first).hasSize(5).isEqualTo(second);
        assertThat(first).extracting(bar -> bar.getTs().toLocalDate())
                .containsExactly(MONDAY, MONDAY.plusDays(1), MONDAY.plusDays(2), MONDAY.plusDays(3), FRIDAY);
        assertThat(first).extracting(Bar::getTs).isSorted();
    }

    @Test
    void dailyBarsSatisfyOhlcInvariants() {
        for (Bar bar : provider.fetchDailyBars("MSFT", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31))) {
            assertSane(bar);
        }
    }

    @Test
    void intradayBarsCoverTheTradingSession() {
        List<Bar> bars = provider.fetchIntradayBars("NVDA", MONDAY, MONDAY);

        assertThat(bars).hasSize(78);
        assertThat(bars.get(0).getTs()).isEqualTo(Instant.parse("2026-06-01T13:30:00Z").atOffset(ZoneOffset.UTC));
        assertThat(bars.get(bars.size() - 1).getTs())
                .isEqualTo(Instant.parse("2026-06-01T19:55:00Z").atOffset(ZoneOffset.UTC));
        assertThat(bars).extracting(Bar::getTs).isSorted();
        for (Bar bar : bars) {
            assertSane(bar);
        }
    }

    @Test
    void differentSymbolsProduceDifferentSeries() {
        double aapl = provider.fetchDailyBars("AAPL", MONDAY, MONDAY).get(0).getClose();
        double msft = provider.fetchDailyBars("MSFT", MONDAY, MONDAY).get(0).getClose();

        assertThat(aapl).isNotEqualTo(msft);
    }

    @Test
    void quoteIsPresentAndSane() {
        Optional<QuoteSnapshot> quote = provider.fetchQuote("AAPL");

        assertThat(quote).isPresent();
        assertThat(quote.get().bid()).isLessThan(quote.get().ask());
        assertThat(quote.get().last()).isPositive();
    }

    @Test
    void searchMatchesSymbolsAndNames() {
        List<SymbolMatch> matches = provider.search("app", 10);

        assertThat(matches).extracting(SymbolMatch::getSymbol).contains("AAPL");
        assertThat(provider.search("AAPL", 1)).hasSize(1);
    }

    @Test
    void sourceIsSynthetic() {
        assertThat(provider.source()).isEqualTo("synthetic");
    }

    private static void assertSane(Bar bar) {
        assertThat(bar.getTs()).isNotNull();
        assertThat(bar.getOpen()).isPositive();
        assertThat(bar.getHigh()).isGreaterThanOrEqualTo(bar.getLow());
        assertThat(bar.getClose()).isBetween(bar.getLow(), bar.getHigh());
        assertThat(bar.getVolume()).isPositive();
    }
}
