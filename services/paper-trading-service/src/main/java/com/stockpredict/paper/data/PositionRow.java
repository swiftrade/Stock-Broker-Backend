package com.stockpredict.paper.data;

/** Row of {@code positions}. */
public record PositionRow(
        String symbol,
        double quantity,
        double avgEntryPrice,
        double realizedPnl) {
}
