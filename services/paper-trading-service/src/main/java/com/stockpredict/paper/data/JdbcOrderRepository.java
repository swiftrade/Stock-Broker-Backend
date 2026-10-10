package com.stockpredict.paper.data;

import com.stockpredict.common.jdbc.JdbcUtils;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** JdbcTemplate implementation of {@link OrderRepository}. */
@Repository
public class JdbcOrderRepository implements OrderRepository {

    private static final String SELECT_COLUMNS = """
            id, account_id, symbol, side, type, quantity, limit_price, stop_price,
            time_in_force, status, filled_quantity, avg_fill_price, reject_reason,
            idempotency_key, submitted_at, updated_at, expires_at
            """;

    private static final RowMapper<OrderRow> MAPPER = (rs, rowNum) -> new OrderRow(
            rs.getObject("id", UUID.class),
            rs.getObject("account_id", UUID.class),
            rs.getString("symbol"),
            rs.getString("side"),
            rs.getString("type"),
            rs.getDouble("quantity"),
            JdbcUtils.nullableDouble(rs, "limit_price"),
            JdbcUtils.nullableDouble(rs, "stop_price"),
            rs.getString("time_in_force"),
            rs.getString("status"),
            rs.getDouble("filled_quantity"),
            JdbcUtils.nullableDouble(rs, "avg_fill_price"),
            rs.getString("reject_reason"),
            rs.getString("idempotency_key"),
            toInstant(rs, "submitted_at"),
            toInstant(rs, "updated_at"),
            toInstant(rs, "expires_at"));

    private final JdbcTemplate jdbc;

    public JdbcOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public OrderRow insert(OrderRow order) {
        jdbc.update("""
                INSERT INTO orders (id, account_id, symbol, side, type, quantity, limit_price, stop_price,
                                    time_in_force, status, filled_quantity, avg_fill_price, reject_reason,
                                    idempotency_key, submitted_at, updated_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, ps -> {
            ps.setObject(1, order.id());
            ps.setObject(2, order.accountId());
            ps.setString(3, order.symbol());
            ps.setString(4, order.side());
            ps.setString(5, order.type());
            ps.setDouble(6, order.quantity());
            JdbcUtils.setNullableDouble(ps, 7, order.limitPrice());
            JdbcUtils.setNullableDouble(ps, 8, order.stopPrice());
            ps.setString(9, order.timeInForce());
            ps.setString(10, order.status());
            ps.setDouble(11, order.filledQuantity());
            JdbcUtils.setNullableDouble(ps, 12, order.avgFillPrice());
            ps.setString(13, order.rejectReason());
            ps.setString(14, order.idempotencyKey());
            setInstant(ps, 15, order.submittedAt());
            setInstant(ps, 16, order.updatedAt());
            setInstant(ps, 17, order.expiresAt());
        });
        return order;
    }

    @Override
    public Optional<OrderRow> findById(UUID orderId) {
        return jdbc.query("SELECT " + SELECT_COLUMNS + " FROM orders WHERE id = ?", MAPPER, orderId)
                .stream().findFirst();
    }

    @Override
    public Optional<OrderRow> findByIdempotencyKey(UUID accountId, String idempotencyKey) {
        return jdbc.query("SELECT " + SELECT_COLUMNS + " FROM orders WHERE account_id = ? AND idempotency_key = ?",
                        MAPPER, accountId, idempotencyKey)
                .stream().findFirst();
    }

    @Override
    public List<OrderRow> findByAccount(UUID accountId, String status, int limit) {
        if (status == null) {
            return jdbc.query("SELECT " + SELECT_COLUMNS
                            + " FROM orders WHERE account_id = ? ORDER BY submitted_at DESC, id DESC LIMIT ?",
                    MAPPER, accountId, limit);
        }
        return jdbc.query("SELECT " + SELECT_COLUMNS
                        + " FROM orders WHERE account_id = ? AND status = ?"
                        + " ORDER BY submitted_at DESC, id DESC LIMIT ?",
                MAPPER, accountId, status, limit);
    }

    @Override
    public List<OrderRow> findWorkingOrders() {
        return jdbc.query("SELECT " + SELECT_COLUMNS
                + " FROM orders WHERE status IN ('NEW', 'PARTIALLY_FILLED') ORDER BY submitted_at ASC", MAPPER);
    }

    @Override
    public boolean updateStatus(UUID orderId, String status, String rejectReason) {
        return jdbc.update("""
                UPDATE orders SET status = ?, reject_reason = ?, updated_at = ?
                WHERE id = ?
                """, status, rejectReason, OffsetDateTime.now(ZoneOffset.UTC), orderId) > 0;
    }

    @Override
    public boolean cancel(UUID orderId) {
        return jdbc.update("""
                UPDATE orders SET status = 'CANCELLED', updated_at = ?
                WHERE id = ? AND status IN ('NEW', 'PARTIALLY_FILLED')
                """, OffsetDateTime.now(ZoneOffset.UTC), orderId) > 0;
    }

    @Override
    public int cancelWorkingForAccount(UUID accountId) {
        return jdbc.update("""
                UPDATE orders SET status = 'CANCELLED', updated_at = ?
                WHERE account_id = ? AND status IN ('NEW', 'PARTIALLY_FILLED')
                """, OffsetDateTime.now(ZoneOffset.UTC), accountId);
    }

    @Override
    public boolean fillIfWorking(UUID orderId, double filledQuantity, double avgFillPrice) {
        return jdbc.update("""
                UPDATE orders
                SET filled_quantity = ?, avg_fill_price = ?, status = 'FILLED', updated_at = ?
                WHERE id = ? AND status IN ('NEW', 'PARTIALLY_FILLED')
                """, filledQuantity, avgFillPrice, OffsetDateTime.now(ZoneOffset.UTC), orderId) > 0;
    }

    @Override
    public int expireDue(Instant now) {
        return jdbc.update("""
                UPDATE orders SET status = 'EXPIRED', updated_at = ?
                WHERE time_in_force = 'DAY' AND status IN ('NEW', 'PARTIALLY_FILLED')
                  AND expires_at IS NOT NULL AND expires_at <= ?
                """, OffsetDateTime.now(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC));
    }

    private static void setInstant(PreparedStatement ps, int index, Instant value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            ps.setObject(index, value.atOffset(ZoneOffset.UTC));
        }
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
