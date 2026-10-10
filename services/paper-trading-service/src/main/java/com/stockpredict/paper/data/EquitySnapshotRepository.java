package com.stockpredict.paper.data;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Persistence for {@code equity_snapshots}. */
public interface EquitySnapshotRepository {

    void upsert(UUID accountId, LocalDate date, double equity, double cash, double positionsValue,
                double unrealizedPnl, double realizedPnl);

    List<EquitySnapshotRow> findByAccount(UUID accountId);
}
