package com.stockpredict.marketdata.provider;

import java.time.OffsetDateTime;

/** Provider-neutral top-of-book quote. */
public record QuoteSnapshot(double bid, double ask, double last, OffsetDateTime ts) {
}
