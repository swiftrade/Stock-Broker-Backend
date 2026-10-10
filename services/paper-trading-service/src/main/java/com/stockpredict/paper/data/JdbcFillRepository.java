package com.stockpredict.paper.data;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JdbcTemplate implementation of {@link FillRepository}. */
@Repository
public class JdbcFillRepository implements FillRepository {

    private final JdbcTemplate jdbc;

    public JdbcFillRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(FillRow fill) {
        jdbc.update("""
                INSERT INTO fills (order_id, account_id, symbol, quantity, price, slippage_bps, filled_at, quote_ts)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, ps -> {
            ps.setObject(1, fill.orderId());
            ps.setObject(2, fill.accountId());
            ps.setString(3, fill.symbol());
            ps.setDouble(4, fill.quantity());
            ps.setDouble(5, fill.price());
            ps.setDouble(6, fill.slippageBps());
            ps.setObject(7, fill.filledAt() == null
                    ? OffsetDateTime.now(ZoneOffset.UTC)
                    : fill.filledAt().atOffset(ZoneOffset.UTC));
            if (fill.quoteTs() == null) {
                ps.setNull(8, Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                ps.setObject(8, fill.quoteTs().atOffset(ZoneOffset.UTC));
            }
        });
    }
}
