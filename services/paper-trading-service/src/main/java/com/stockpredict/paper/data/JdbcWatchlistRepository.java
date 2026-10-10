package com.stockpredict.paper.data;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** JdbcTemplate implementation of {@link WatchlistRepository}. */
@Repository
public class JdbcWatchlistRepository implements WatchlistRepository {

    private final JdbcTemplate jdbc;

    public JdbcWatchlistRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<String> findByUserId(UUID userId) {
        return jdbc.queryForList("SELECT symbol FROM watchlists WHERE user_id = ? ORDER BY symbol",
                String.class, userId);
    }

    @Override
    @Transactional
    public void replaceAll(UUID userId, List<String> symbols) {
        jdbc.update("DELETE FROM watchlists WHERE user_id = ?", userId);
        if (!symbols.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO watchlists (user_id, symbol) VALUES (?, ?)",
                    symbols, symbols.size(), (ps, symbol) -> {
                        ps.setObject(1, userId);
                        ps.setString(2, symbol);
                    });
        }
    }

    @Override
    public List<String> symbolsForAccount(UUID accountId) {
        return jdbc.queryForList("""
                SELECT w.symbol FROM watchlists w
                JOIN paper_accounts a ON a.user_id = w.user_id
                WHERE a.id = ?
                ORDER BY w.symbol
                """, String.class, accountId);
    }
}
