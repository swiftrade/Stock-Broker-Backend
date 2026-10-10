package com.stockpredict.paper.data;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence for {@code positions}. */
public interface PositionRepository {

    List<PositionRow> findByAccount(UUID accountId);

    Optional<PositionRow> find(UUID accountId, String symbol);

    /** Insert or replace the position for (account, symbol). */
    void upsert(UUID accountId, PositionRow position);

    void delete(UUID accountId, String symbol);

    void deleteByAccount(UUID accountId);
}
