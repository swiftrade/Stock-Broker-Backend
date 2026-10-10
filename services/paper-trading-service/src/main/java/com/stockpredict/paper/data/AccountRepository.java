package com.stockpredict.paper.data;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence for {@code paper_accounts}. */
public interface AccountRepository {

    Optional<AccountRow> findById(UUID accountId);

    /**
     * Same as {@link #findById} but takes a row lock ({@code SELECT ... FOR
     * UPDATE}). All ledger mutations per account serialize through this row,
     * which closes the cross-replica position lost-update window.
     */
    Optional<AccountRow> findByIdForUpdate(UUID accountId);

    Optional<AccountRow> findByUserId(UUID userId);

    List<AccountRow> findAll();

    /** Inserts a fresh account; returns the existing row if the user already has one. */
    AccountRow insert(UUID userId, double startingCash);

    /** Restores cash/realized PnL to the starting state, bumps the version and clears the lock. */
    boolean reset(UUID accountId);

    /** Optimistic cash update; returns {@code false} when the version no longer matches. */
    boolean updateCashWithVersion(UUID accountId, double newCash, long expectedVersion);

    /** Optimistic cash + realized-PnL update; returns {@code false} on version conflict. */
    boolean updateCashAndRealizedWithVersion(UUID accountId, double newCash, double newRealizedPnl,
                                             long expectedVersion);

    void setWatchlistLock(UUID userId, Instant lockedUntil);
}
