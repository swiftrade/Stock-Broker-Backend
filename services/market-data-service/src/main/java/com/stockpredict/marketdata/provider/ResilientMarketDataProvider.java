package com.stockpredict.marketdata.provider;

import com.stockpredict.marketdata.api.model.Bar;
import com.stockpredict.marketdata.api.model.SymbolMatch;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Wraps a delegate provider with retry + circuit breaking. */
public class ResilientMarketDataProvider implements MarketDataProvider {

    private final MarketDataProvider delegate;
    private final ProviderResilience resilience;

    public ResilientMarketDataProvider(MarketDataProvider delegate, ProviderResilience resilience) {
        this.delegate = delegate;
        this.resilience = resilience;
    }

    @Override
    public List<Bar> fetchDailyBars(String symbol, LocalDate from, LocalDate to) {
        return resilience.execute(() -> delegate.fetchDailyBars(symbol, from, to));
    }

    @Override
    public List<Bar> fetchIntradayBars(String symbol, LocalDate from, LocalDate to) {
        return resilience.execute(() -> delegate.fetchIntradayBars(symbol, from, to));
    }

    @Override
    public Optional<QuoteSnapshot> fetchQuote(String symbol) {
        return resilience.execute(() -> delegate.fetchQuote(symbol));
    }

    @Override
    public List<SymbolMatch> search(String query, int limit) {
        return resilience.execute(() -> delegate.search(query, limit));
    }

    @Override
    public String source() {
        return delegate.source();
    }
}
