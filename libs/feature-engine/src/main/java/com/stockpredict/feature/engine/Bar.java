package com.stockpredict.feature.engine;

import java.time.Instant;

/** One OHLCV bar. Timestamps are UTC. Volume is double for arithmetic convenience. */
public record Bar(String symbol, Instant timestamp,
                  double open, double high, double low, double close, double volume) {
}
