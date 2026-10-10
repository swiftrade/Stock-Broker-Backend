package com.stockpredict.paper.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** JdbcTemplate implementation of {@link AccountRepository}. */
@Repository
public class JdbcAccountRepository implements AccountRepository {

    private static final RowMapper<AccountRow> MAPPER = (rs, rowNum) -> new AccountRow(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getDouble("cash"),
            rs.getDouble("starting_cash"),
            rs.getDouble("realized_pnl"),
            rs.getString("status"),
            toInstant(rs, "watchlist_locked_until"),
            rs.getLong("version"),
            toInstant(rs, "created_at"));

    private final JdbcTemplate jdbc;

    public JdbcAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AccountRow> findById(UUID accountId) {
        return jdbc.query("SELECT * FROM paper_accounts WHERE id = ?", MAPPER, accountId).stream().findFirst();
    }

    @Override
    public Optional<AccountRow> findByIdForUpdate(UUID accountId) {
        return jdbc.query("SELECT * FROM paper_accounts WHERE id = ? FOR UPDATE", MAPPER, accountId)
                .stream().findFirst();
    }

    @Override
    public Optional<AccountRow> findByUserId(UUID userId) {
        return jdbc.query("SELECT * FROM paper_accounts WHERE user_id = ?", MAPPER, userId).stream().findFirst();
    }

    @Override
    public List<AccountRow> findAll() {
        return jdbc.query("SELECT * FROM paper_accounts ORDER BY created_at", MAPPER);
    }

    @Override
    public AccountRow insert(UUID userId, double startingCash) {
        jdbc.update("""
                INSERT INTO paper_accounts (user_id, cash, starting_cash, realized_pnl, status, version)
                VALUES (?, ?, ?, 0, 'ACTIVE', 0)
                ON CONFLICT (user_id) DO NOTHING
                """, userId, startingCash, startingCash);
        return findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("Account insert failed for user " + userId));
    }

    @Override
    public boolean reset(UUID accountId) {
        return jdbc.update("""
                UPDATE paper_accounts
                SET cash = starting_cash, realized_pnl = 0, watchlist_locked_until = NULL,
                    version = version + 1, updated_at = ?
                WHERE id = ?
                """, OffsetDateTime.now(ZoneOffset.UTC), accountId) > 0;
    }

    @Override
    public boolean updateCashWithVersion(UUID accountId, double newCash, long expectedVersion) {
        return jdbc.update("""
                UPDATE paper_accounts
                SET cash = ?, version = version + 1, updated_at = ?
                WHERE id = ? AND version = ?
                """, newCash, OffsetDateTime.now(ZoneOffset.UTC), accountId, expectedVersion) > 0;
    }

    @Override
    public boolean updateCashAndRealizedWithVersion(UUID accountId, double newCash, double newRealizedPnl,
                                                    long expectedVersion) {
        return jdbc.update("""
                UPDATE paper_accounts
                SET cash = ?, realized_pnl = ?, version = version + 1, updated_at = ?
                WHERE id = ? AND version = ?
                """, newCash, newRealizedPnl, OffsetDateTime.now(ZoneOffset.UTC), accountId, expectedVersion) > 0;
    }

    @Override
    public void setWatchlistLock(UUID userId, Instant lockedUntil) {
        jdbc.update("""
                UPDATE paper_accounts
                SET watchlist_locked_until = ?, updated_at = ?
                WHERE user_id = ?
                """,
                lockedUntil == null ? null : lockedUntil.atOffset(ZoneOffset.UTC),
                OffsetDateTime.now(ZoneOffset.UTC), userId);
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
