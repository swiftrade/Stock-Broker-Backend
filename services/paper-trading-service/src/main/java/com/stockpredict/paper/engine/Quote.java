package com.stockpredict.paper.engine;

import java.time.Instant;

/** Latest market quote used by the matching engine. */
public record Quote(
        String symbol,
        double bid,
        double ask,
        double last,
        Instant ts,
        boolean stale) {

    /**
     * True when the quote can safely price a fill. A zero or negative
     * bid/ask/last (e.g. an empty NBBO passed through) must never match.
     */
    public boolean valid() {
        return bid > 0 && ask > 0 && last > 0 && ask >= bid;
    }
}
