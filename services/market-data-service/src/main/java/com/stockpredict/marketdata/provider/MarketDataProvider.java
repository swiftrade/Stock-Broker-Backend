package com.stockpredict.marketdata.provider;

import com.stockpredict.marketdata.api.model.Bar;
import com.stockpredict.marketdata.api.model.SymbolMatch;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Upstream market-data source (Polygon, synthetic fallback, ...). */
public interface MarketDataProvider {

    List<Bar> fetchDailyBars(String symbol, LocalDate from, LocalDate to);

    List<Bar> fetchIntradayBars(String symbol, LocalDate from, LocalDate to);

    /** Never throws: returns {@link Optional#empty()} when the quote is unavailable. */
    Optional<QuoteSnapshot> fetchQuote(String symbol);

    List<SymbolMatch> search(String query, int limit);

    String source();
}
