package com.stockpredict.paper.data;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** JdbcTemplate implementation of {@link PositionRepository}. */
@Repository
public class JdbcPositionRepository implements PositionRepository {

    private static final RowMapper<PositionRow> MAPPER = (rs, rowNum) -> new PositionRow(
            rs.getString("symbol"),
            rs.getDouble("quantity"),
            rs.getDouble("avg_entry_price"),
            rs.getDouble("realized_pnl"));

    private final JdbcTemplate jdbc;

    public JdbcPositionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PositionRow> findByAccount(UUID accountId) {
        return jdbc.query("""
                SELECT symbol, quantity, avg_entry_price, realized_pnl
                FROM positions WHERE account_id = ? ORDER BY symbol
                """, MAPPER, accountId);
    }

    @Override
    public Optional<PositionRow> find(UUID accountId, String symbol) {
        return jdbc.query("""
                SELECT symbol, quantity, avg_entry_price, realized_pnl
                FROM positions WHERE account_id = ? AND symbol = ?
                """, MAPPER, accountId, symbol).stream().findFirst();
    }

    @Override
    public void upsert(UUID accountId, PositionRow position) {
        jdbc.update("""
                INSERT INTO positions (account_id, symbol, quantity, avg_entry_price, realized_pnl, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (account_id, symbol) DO UPDATE SET
                    quantity = EXCLUDED.quantity,
                    avg_entry_price = EXCLUDED.avg_entry_price,
                    realized_pnl = EXCLUDED.realized_pnl,
                    updated_at = EXCLUDED.updated_at
                """, accountId, position.symbol(), position.quantity(), position.avgEntryPrice(),
                position.realizedPnl(), OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Override
    public void delete(UUID accountId, String symbol) {
        jdbc.update("DELETE FROM positions WHERE account_id = ? AND symbol = ?", accountId, symbol);
    }

    @Override
    public void deleteByAccount(UUID accountId) {
        jdbc.update("DELETE FROM positions WHERE account_id = ?", accountId);
    }
}
