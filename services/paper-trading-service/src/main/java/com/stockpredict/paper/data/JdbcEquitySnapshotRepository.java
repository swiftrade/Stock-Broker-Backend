package com.stockpredict.paper.data;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** JdbcTemplate implementation of {@link EquitySnapshotRepository}. */
@Repository
public class JdbcEquitySnapshotRepository implements EquitySnapshotRepository {

    private static final RowMapper<EquitySnapshotRow> MAPPER = (rs, rowNum) -> new EquitySnapshotRow(
            rs.getDate("snapshot_date").toLocalDate(),
            rs.getDouble("equity"),
            rs.getDouble("cash"),
            rs.getDouble("positions_value"),
            rs.getDouble("unrealized_pnl"),
            rs.getDouble("realized_pnl"));

    private final JdbcTemplate jdbc;

    public JdbcEquitySnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(UUID accountId, LocalDate date, double equity, double cash, double positionsValue,
                       double unrealizedPnl, double realizedPnl) {
        jdbc.update("""
                INSERT INTO equity_snapshots
                    (account_id, snapshot_date, equity, cash, positions_value, unrealized_pnl, realized_pnl)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (account_id, snapshot_date) DO UPDATE SET
                    equity = EXCLUDED.equity,
                    cash = EXCLUDED.cash,
                    positions_value = EXCLUDED.positions_value,
                    unrealized_pnl = EXCLUDED.unrealized_pnl,
                    realized_pnl = EXCLUDED.realized_pnl
                """, accountId, java.sql.Date.valueOf(date), equity, cash, positionsValue,
                unrealizedPnl, realizedPnl);
    }

    @Override
    public List<EquitySnapshotRow> findByAccount(UUID accountId) {
        return jdbc.query("""
                SELECT snapshot_date, equity, cash, positions_value, unrealized_pnl, realized_pnl
                FROM equity_snapshots WHERE account_id = ? ORDER BY snapshot_date
                """, MAPPER, accountId);
    }
}
