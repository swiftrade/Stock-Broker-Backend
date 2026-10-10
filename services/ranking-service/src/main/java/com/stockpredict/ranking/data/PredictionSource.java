package com.stockpredict.ranking.data;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads the 20d predictions produced by A4 plus realized volatility from bars. */
@Repository
public class PredictionSource {

    /** Annualization factor for daily volatility. */
    private static final double TRADING_DAYS_PER_YEAR = 252.0;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public PredictionSource(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
    }

    /** Top candidates by predicted 20d log return at the latest as-of date. */
    public List<PredictionSnapshot> topByPredictedReturn(int horizonDays, int limit) {
        return jdbc.query("""
                SELECT symbol, predicted_log_return, model_version, features::text AS features
                FROM predictions
                WHERE horizon_days = ? AND as_of = (SELECT max(as_of) FROM predictions WHERE horizon_days = ?)
                ORDER BY predicted_log_return DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new PredictionSnapshot(
                        rs.getString("symbol"),
                        rs.getDouble("predicted_log_return"),
                        rs.getString("model_version"),
                        rs.getString("features")),
                horizonDays, horizonDays, limit);
    }

    public Optional<LocalDate> latestAsOf(int horizonDays) {
        return jdbc.query("SELECT max(as_of) AS as_of FROM predictions WHERE horizon_days = ?",
                rs -> rs.next() && rs.getDate("as_of") != null
                        ? Optional.of(rs.getDate("as_of").toLocalDate())
                        : Optional.empty(),
                horizonDays);
    }

    /**
     * Annualized realized volatility from daily log returns over the trailing
     * window ending at (and including) the latest bar at or before {@code asOf}.
     */
    public java.util.Map<String, Double> realizedVolatility(Collection<String> symbols,
                                                            LocalDate asOf, int windowDays) {
        if (symbols == null || symbols.isEmpty()) {
            return java.util.Map.of();
        }
        String sql = """
                SELECT symbol, close FROM (
                    SELECT symbol, ts, close,
                           row_number() OVER (PARTITION BY symbol ORDER BY ts DESC) AS rn
                    FROM bars_daily
                    WHERE symbol IN (:symbols) AND ts < :before
                ) ranked
                WHERE rn <= :window
                ORDER BY symbol, rn DESC
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("symbols", symbols)
                .addValue("before", java.sql.Timestamp.from(
                        com.stockpredict.common.time.BarTimestamps.dailyWindowEnd(asOf)))
                .addValue("window", windowDays + 1);
        java.util.Map<String, List<Double>> closesBySymbol = namedJdbc.query(sql, params,
                (rs, rowNum) -> java.util.Map.entry(rs.getString("symbol"), rs.getDouble("close")))
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(java.util.Map.Entry::getKey,
                        java.util.stream.Collectors.mapping(java.util.Map.Entry::getValue,
                                java.util.stream.Collectors.toList())));

        java.util.Map<String, Double> volatility = new java.util.HashMap<>();
        closesBySymbol.forEach((symbol, closes) -> volatility.put(symbol, annualizedVolatility(closes)));
        return volatility;
    }

    static double annualizedVolatility(List<Double> closes) {
        if (closes == null || closes.size() < 3) {
            return Double.NaN;
        }
        double[] returns = new double[closes.size() - 1];
        for (int i = 1; i < closes.size(); i++) {
            double previous = closes.get(i - 1);
            returns[i - 1] = previous > 0 ? Math.log(closes.get(i) / previous) : Double.NaN;
        }
        double mean = 0;
        int count = 0;
        for (double value : returns) {
            if (!Double.isNaN(value)) {
                mean += value;
                count++;
            }
        }
        if (count < 2) {
            return Double.NaN;
        }
        mean /= count;
        double sumSq = 0;
        for (double value : returns) {
            if (!Double.isNaN(value)) {
                sumSq += (value - mean) * (value - mean);
            }
        }
        double dailyStd = Math.sqrt(sumSq / (count - 1));
        return dailyStd * Math.sqrt(TRADING_DAYS_PER_YEAR);
    }

    /** Prediction row with its stored feature snapshot. */
    public record PredictionSnapshot(String symbol, double predictedLogReturn,
                                     String modelVersion, String featuresJson) {
    }
}
