package com.stockpredict.paper.data;

import java.util.List;
import java.util.UUID;

/** Persistence for {@code watchlists}. */
public interface WatchlistRepository {

    /** Symbols for the user, ordered by symbol. */
    List<String> findByUserId(UUID userId);

    /** Transactional delete + insert of the whole watchlist. */
    void replaceAll(UUID userId, List<String> symbols);

    /** Symbols tradable by the account's owner (join through {@code paper_accounts}). */
    List<String> symbolsForAccount(UUID accountId);
}
