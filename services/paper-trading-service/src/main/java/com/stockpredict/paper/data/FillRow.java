package com.stockpredict.paper.data;

import java.time.Instant;
import java.util.UUID;

/** Row of {@code fills}. */
public record FillRow(
        UUID orderId,
        UUID accountId,
        String symbol,
        double quantity,
        double price,
        double slippageBps,
        Instant filledAt,
        Instant quoteTs) {
}
