package com.stockpredict.paper.engine;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fallback quote source: latest daily close from {@code bars_daily}. Quotes are
 * marked stale because they are end-of-day values, not live prices.
 */
public class DatabaseQuoteProvider implements QuoteProvider {

    private final JdbcTemplate jdbc;

    public DatabaseQuoteProvider(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Quote> quote(String symbol) {
        return jdbc.query("""
                SELECT close, ts FROM bars_daily WHERE symbol = ? ORDER BY ts DESC LIMIT 1
                """, rs -> {
            if (!rs.next()) {
                return Optional.empty();
            }
            double close = rs.getDouble("close");
            OffsetDateTime ts = rs.getObject("ts", OffsetDateTime.class);
            return Optional.of(new Quote(symbol, close, close, close,
                    ts == null ? null : ts.toInstant(), true));
        }, symbol);
    }
}
