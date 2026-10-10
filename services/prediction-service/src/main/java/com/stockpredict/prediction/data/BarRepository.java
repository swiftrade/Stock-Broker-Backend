package com.stockpredict.prediction.data;

import com.stockpredict.feature.engine.Bar;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads cached daily bars from the shared {@code bars_daily} table. */
@Repository
public class BarRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public BarRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Bar> loadBars(Collection<String> symbols, Instant from, Instant to) {
        if (symbols == null || symbols.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT symbol, ts, open, high, low, close, volume FROM bars_daily "
                + "WHERE symbol IN (:symbols) AND ts >= :from AND ts <= :to ORDER BY symbol, ts";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("symbols", symbols)
                .addValue("from", Timestamp.from(from))
                .addValue("to", Timestamp.from(to));
        return jdbc.query(sql, params, (rs, rowNum) -> new Bar(
                rs.getString("symbol"),
                rs.getTimestamp("ts").toInstant(),
                rs.getDouble("open"),
                rs.getDouble("high"),
                rs.getDouble("low"),
                rs.getDouble("close"),
                rs.getDouble("volume")));
    }

    public List<String> listSymbols() {
        return jdbc.queryForList("SELECT DISTINCT symbol FROM bars_daily ORDER BY symbol", new MapSqlParameterSource(), String.class);
    }

    public Optional<Instant> latestTimestamp() {
        return jdbc.query("SELECT max(ts) AS ts FROM bars_daily", rs -> {
            if (rs.next() && rs.getTimestamp("ts") != null) {
                return Optional.of(rs.getTimestamp("ts").toInstant());
            }
            return Optional.empty();
        });
    }
}
