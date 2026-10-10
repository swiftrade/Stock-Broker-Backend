package com.stockpredict.marketdata.guard;

import com.stockpredict.marketdata.config.MarketDataProperties;
import com.stockpredict.marketdata.provider.QuoteSnapshot;
import org.springframework.stereotype.Component;

/**
 * Sanity checks applied at the single point where provider quotes enter the
 * system. Bars are protected by {@link IngestionQualityGuard}; quotes were not,
 * and a zero/one-sided quote would fill paper orders at zero.
 */
@Component
public class QuoteGuard {

    private final MarketDataProperties properties;

    public QuoteGuard(MarketDataProperties properties) {
        this.properties = properties;
    }

    /** True when every field is finite, strictly positive and the spread is sane. */
    public boolean isValid(QuoteSnapshot quote) {
        if (quote == null) {
            return false;
        }
        // Negated comparisons also reject NaN.
        if (!(quote.bid() > 0) || !(quote.ask() > 0) || !(quote.last() > 0)) {
            return false;
        }
        if (quote.ask() < quote.bid()) {
            return false;
        }
        double mid = (quote.bid() + quote.ask()) / 2.0;
        double relativeSpread = (quote.ask() - quote.bid()) / mid;
        return relativeSpread <= properties.getGuard().getMaxQuoteSpreadRatio();
    }
}
