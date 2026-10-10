package com.stockpredict.paper.data;

import java.time.Instant;
import java.util.UUID;

/** Row of {@code orders}. */
public record OrderRow(
        UUID id,
        UUID accountId,
        String symbol,
        String side,
        String type,
        double quantity,
        Double limitPrice,
        Double stopPrice,
        String timeInForce,
        String status,
        double filledQuantity,
        Double avgFillPrice,
        String rejectReason,
        String idempotencyKey,
        Instant submittedAt,
        Instant updatedAt,
        Instant expiresAt) {

    public double remainingQuantity() {
        return quantity - filledQuantity;
    }
}
