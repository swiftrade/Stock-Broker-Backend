package com.stockpredict.paper.data;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence for {@code orders}. */
public interface OrderRepository {

    OrderRow insert(OrderRow order);

    Optional<OrderRow> findById(UUID orderId);

    Optional<OrderRow> findByIdempotencyKey(UUID accountId, String idempotencyKey);

    /** Orders for the account, newest first; {@code status} may be null. */
    List<OrderRow> findByAccount(UUID accountId, String status, int limit);

    /** Working orders (NEW / PARTIALLY_FILLED), oldest first. */
    List<OrderRow> findWorkingOrders();

    boolean updateStatus(UUID orderId, String status, String rejectReason);

    /** Cancels only if the order is still working. */
    boolean cancel(UUID orderId);

    int cancelWorkingForAccount(UUID accountId);

    /**
     * Marks a working order fully filled. Returns {@code false} when the order is
     * no longer working, which is the guard against double fills.
     */
    boolean fillIfWorking(UUID orderId, double filledQuantity, double avgFillPrice);

    /** Expires DAY orders whose {@code expires_at} has passed. */
    int expireDue(Instant now);
}
