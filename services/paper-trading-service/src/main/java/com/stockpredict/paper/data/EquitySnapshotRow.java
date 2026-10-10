package com.stockpredict.paper.data;

import java.time.LocalDate;

/** Row of {@code equity_snapshots}. */
public record EquitySnapshotRow(
        LocalDate snapshotDate,
        double equity,
        double cash,
        double positionsValue,
        double unrealizedPnl,
        double realizedPnl) {
}
