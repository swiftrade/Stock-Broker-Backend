package com.stockpredict.ranking.data;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence for nightly rankings; history is retained per run date. */
@Repository
public class RankingRepository {

    private static final String UPSERT = """
            INSERT INTO rankings (run_date, model_version, symbol, rank, rank_change,
                                  predicted_20d_return, volatility, sector, weight, rationale)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (run_date, symbol) DO UPDATE SET
                model_version = EXCLUDED.model_version,
                rank = EXCLUDED.rank,
                rank_change = EXCLUDED.rank_change,
                predicted_20d_return = EXCLUDED.predicted_20d_return,
                volatility = EXCLUDED.volatility,
                sector = EXCLUDED.sector,
                weight = EXCLUDED.weight,
                rationale = EXCLUDED.rationale
            """;

    private final JdbcTemplate jdbc;

    public RankingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsertAll(List<RankingRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(UPSERT, rows, rows.size(), (ps, row) -> {
            ps.setDate(1, Date.valueOf(row.runDate()));
            ps.setString(2, row.modelVersion());
            ps.setString(3, row.symbol());
            ps.setInt(4, row.rank());
            if (row.rankChange() == null) {
                ps.setNull(5, java.sql.Types.INTEGER);
            } else {
                ps.setInt(5, row.rankChange());
            }
            ps.setDouble(6, row.predicted20dReturn());
            if (row.volatility() == null) {
                ps.setNull(7, java.sql.Types.DOUBLE);
            } else {
                ps.setDouble(7, row.volatility());
            }
            ps.setString(8, row.sector());
            ps.setDouble(9, row.weight());
            ps.setString(10, row.rationaleJson());
        });
    }

    public Optional<LocalDate> latestRunDate() {
        return jdbc.query("SELECT max(run_date) AS run_date FROM rankings",
                rs -> rs.next() && rs.getDate("run_date") != null
                        ? Optional.of(rs.getDate("run_date").toLocalDate())
                        : Optional.empty());
    }

    public List<RankingRow> findByRunDate(LocalDate runDate, int limit) {
        return jdbc.query("""
                SELECT run_date, model_version, symbol, rank, rank_change, predicted_20d_return,
                       volatility, sector, weight, rationale::text AS rationale
                FROM rankings WHERE run_date = ? ORDER BY rank LIMIT ?
                """,
                (rs, rowNum) -> new RankingRow(
                        rs.getDate("run_date").toLocalDate(),
                        rs.getString("model_version"),
                        rs.getString("symbol"),
                        rs.getInt("rank"),
                        (Integer) rs.getObject("rank_change"),
                        rs.getDouble("predicted_20d_return"),
                        nullableDouble(rs, "volatility"),
                        rs.getString("sector"),
                        rs.getDouble("weight"),
                        rs.getString("rationale")),
                Date.valueOf(runDate), limit);
    }

    /** Ranks from the most recent run strictly before {@code beforeDate}. */
    public Map<String, Integer> previousRanks(LocalDate beforeDate) {
        return jdbc.query("""
                SELECT symbol, rank FROM rankings
                WHERE run_date = (SELECT max(run_date) FROM rankings WHERE run_date < ?)
                """,
                (rs, rowNum) -> Map.entry(rs.getString("symbol"), rs.getInt("rank")),
                Date.valueOf(beforeDate))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static Double nullableDouble(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
