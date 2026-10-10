package com.stockpredict.paper.engine;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Tries providers in order, returning the first quote available. */
public class CompositeQuoteProvider implements QuoteProvider {

    private final List<QuoteProvider> providers;

    public CompositeQuoteProvider(List<QuoteProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    @Override
    public Optional<Quote> quote(String symbol) {
        for (QuoteProvider provider : providers) {
            Optional<Quote> quote = provider.quote(symbol);
            if (quote.isPresent()) {
                return quote;
            }
        }
        return Optional.empty();
    }

    @Override
    public Map<String, Quote> quotes(Collection<String> symbols) {
        Map<String, Quote> result = new LinkedHashMap<>();
        Set<String> remaining = new LinkedHashSet<>(symbols);
        for (QuoteProvider provider : providers) {
            if (remaining.isEmpty()) {
                break;
            }
            Map<String, Quote> fetched = provider.quotes(remaining);
            fetched.forEach((symbol, quote) -> {
                if (remaining.remove(symbol)) {
                    result.put(symbol, quote);
                }
            });
        }
        return result;
    }
}
