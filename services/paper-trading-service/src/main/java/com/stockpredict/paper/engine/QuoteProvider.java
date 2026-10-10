package com.stockpredict.paper.engine;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Source of the latest quote for a symbol. */
public interface QuoteProvider {

    Optional<Quote> quote(String symbol);

    /**
     * Batch lookup; providers with a multi-symbol transport override this to
     * avoid an N+1 HTTP fan-out when valuing portfolios.
     */
    default Map<String, Quote> quotes(Collection<String> symbols) {
        Map<String, Quote> quotes = new LinkedHashMap<>();
        for (String symbol : symbols) {
            quote(symbol).ifPresent(quote -> quotes.put(symbol, quote));
        }
        return quotes;
    }
}
