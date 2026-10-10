package com.stockpredict.marketdata.ingest;

import com.stockpredict.common.jdbc.JdbcUtils;
import com.stockpredict.marketdata.api.model.Bar;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads/writes the shared {@code bars_daily} and {@code bars_5m} tables. The
 * generated {@link Bar} model carries no symbol, so the owning symbol and the
 * provider source are passed alongside each batch.
 */
@Repository
public class BarRepository {

    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;

    public BarRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int upsertDaily(String symbol, List<Bar> bars) {
        return upsertDaily(symbol, "cache", bars);
    }

    public int upsertDaily(String symbol, String source, List<Bar> bars) {
        return upsert("bars_daily", symbol, source, bars);
    }

    public int upsertIntraday(String symbol, List<Bar> bars) {
        return upsertIntraday(symbol, "cache", bars);
    }

    public int upsertIntraday(String symbol, String source, List<Bar> bars) {
        return upsert("bars_5m", symbol, source, bars);
    }

    private int upsert(String table, String symbol, String source, List<Bar> bars) {
        if (bars == null || bars.isEmpty()) {
            return 0;
        }
        String sql = "INSERT INTO " + table
                + " (symbol, ts, open, high, low, close, volume, vwap, trade_count, source) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (symbol, ts) DO UPDATE SET open = EXCLUDED.open, high = EXCLUDED.high, "
                + "low = EXCLUDED.low, close = EXCLUDED.close, volume = EXCLUDED.volume, "
                + "vwap = EXCLUDED.vwap, trade_count = EXCLUDED.trade_count, "
                + "source = EXCLUDED.source, ingested_at = now()";
        String effectiveSource = source == null ? "cache" : source;
        int[][] rows = jdbc.batchUpdate(sql, bars, BATCH_SIZE, (ps, bar) -> {
            ps.setString(1, symbol);
            ps.setTimestamp(2, Timestamp.from(bar.getTs().toInstant()));
            ps.setDouble(3, bar.getOpen());
            ps.setDouble(4, bar.getHigh());
            ps.setDouble(5, bar.getLow());
            ps.setDouble(6, bar.getClose());
            ps.setLong(7, bar.getVolume() == null ? 0L : bar.getVolume());
            if (bar.getVwap() == null) {
                ps.setNull(8, Types.NUMERIC);
            } else {
                ps.setDouble(8, bar.getVwap());
            }
            if (bar.getTradeCount() == null) {
                ps.setNull(9, Types.BIGINT);
            } else {
                ps.setLong(9, bar.getTradeCount());
            }
            ps.setString(10, effectiveSource);
        });
        int updated = 0;
        for (int[] row : rows) {
            for (int count : row) {
                updated += count;
            }
        }
        return updated;
    }

    public List<Bar> loadDaily(String symbol, LocalDate from, LocalDate to) {
        return load("bars_daily", symbol, from, to);
    }

    public List<Bar> loadIntraday(String symbol, LocalDate from, LocalDate to) {
        return load("bars_5m", symbol, from, to);
    }

    private List<Bar> load(String table, String symbol, LocalDate from, LocalDate to) {
        String sql = "SELECT ts, open, high, low, close, volume, vwap, trade_count FROM " + table
                + " WHERE symbol = ? AND ts >= ? AND ts < ? ORDER BY ts";
        return jdbc.query(sql, (rs, rowNum) -> mapRow(rs),
                symbol,
                Timestamp.from(from.atStartOfDay(ZoneOffset.UTC).toInstant()),
                Timestamp.from(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
    }

    public Optional<Instant> latestDailyTs(String symbol) {
        List<Instant> rows = jdbc.query(
                "SELECT ts FROM bars_daily WHERE symbol = ? ORDER BY ts DESC LIMIT 1",
                (rs, rowNum) -> rs.getTimestamp("ts").toInstant(), symbol);
        return rows.stream().findFirst();
    }

    public Optional<Bar> latestDailyBar(String symbol) {
        List<Bar> rows = jdbc.query(
                "SELECT ts, open, high, low, close, volume, vwap, trade_count FROM bars_daily "
                        + "WHERE symbol = ? ORDER BY ts DESC LIMIT 1",
                (rs, rowNum) -> mapRow(rs), symbol);
        return rows.stream().findFirst();
    }

    public Optional<LocalDate> latestDailyTimestamp(String symbol) {
        return latestDailyTs(symbol).map(ts -> ts.atZone(ZoneOffset.UTC).toLocalDate());
    }

    public List<String> listSymbols() {
        return jdbc.queryForList("SELECT DISTINCT symbol FROM bars_daily ORDER BY symbol", String.class);
    }

    public List<String> listUniverseSymbols() {
        return jdbc.queryForList("SELECT symbol FROM symbol_universe ORDER BY symbol", String.class);
    }

    public boolean isUniverseSymbol(String symbol) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM symbol_universe WHERE symbol = ?)",
                Boolean.class, symbol);
        return Boolean.TRUE.equals(exists);
    }

    public boolean hasBars(String symbol) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM bars_daily WHERE symbol = ? "
                        + "UNION ALL SELECT 1 FROM bars_5m WHERE symbol = ?)",
                Boolean.class, symbol, symbol);
        return Boolean.TRUE.equals(exists);
    }

    public int registerUniverseSymbols(Collection<String> symbols, String source) {
        if (symbols == null || symbols.isEmpty()) {
            return 0;
        }
        String effectiveSource = source == null ? "universe" : source;
        int[][] rows = jdbc.batchUpdate(
                "INSERT INTO symbol_universe (symbol, source) VALUES (?, ?) "
                        + "ON CONFLICT (symbol) DO NOTHING",
                symbols, BATCH_SIZE, (ps, symbol) -> {
                    ps.setString(1, symbol);
                    ps.setString(2, effectiveSource);
                });
        int inserted = 0;
        for (int[] row : rows) {
            for (int count : row) {
                inserted += count;
            }
        }
        return inserted;
    }

    public List<String> searchSymbols(String query, int limit) {
        String pattern = "%" + (query == null ? "" : query.trim().toUpperCase(Locale.ROOT)) + "%";
        return jdbc.queryForList(
                "SELECT DISTINCT symbol FROM bars_daily WHERE symbol ILIKE ? ORDER BY symbol LIMIT ?",
                String.class, pattern, Math.max(1, limit));
    }

    static Bar mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Bar()
                .ts(rs.getTimestamp("ts").toInstant().atOffset(ZoneOffset.UTC))
                .open(rs.getDouble("open"))
                .high(rs.getDouble("high"))
                .low(rs.getDouble("low"))
                .close(rs.getDouble("close"))
                .volume(rs.getLong("volume"))
                .vwap(JdbcUtils.nullableDouble(rs, "vwap"))
                .tradeCount(JdbcUtils.nullableLong(rs, "trade_count"));
    }
}
