package com.stockpredict.paper.data;

import java.time.Instant;
import java.util.UUID;

/** Row of {@code paper_accounts}. */
public record AccountRow(
        UUID id,
        UUID userId,
        double cash,
        double startingCash,
        double realizedPnl,
        String status,
        Instant watchlistLockedUntil,
        long version,
        Instant createdAt) {
}
