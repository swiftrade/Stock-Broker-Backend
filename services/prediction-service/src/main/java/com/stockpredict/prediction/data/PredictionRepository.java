package com.stockpredict.prediction.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpredict.common.jdbc.JdbcUtils;
import com.stockpredict.prediction.model.ModelMetadata;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence for precomputed predictions and the model registry table. */
@Repository
public class PredictionRepository {

    private static final String UPSERT = """
            INSERT INTO predictions (model_version, as_of, symbol, horizon_days, predicted_log_return,
                                     p_up, confidence, current_close, predicted_price, features)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (model_version, as_of, symbol, horizon_days) DO UPDATE SET
                predicted_log_return = EXCLUDED.predicted_log_return,
                p_up = EXCLUDED.p_up,
                confidence = EXCLUDED.confidence,
                current_close = EXCLUDED.current_close,
                predicted_price = EXCLUDED.predicted_price,
                features = EXCLUDED.features
            """;

    private static final String SELECT = """
            SELECT model_version, as_of, symbol, horizon_days, predicted_log_return,
                   p_up, confidence, current_close, predicted_price, features::text AS features
            FROM predictions
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PredictionRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void upsertAll(List<PredictionRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(UPSERT, rows, rows.size(), (ps, row) -> {
            ps.setString(1, row.modelVersion());
            ps.setDate(2, Date.valueOf(row.asOf()));
            ps.setString(3, row.symbol());
            ps.setInt(4, row.horizonDays());
            ps.setDouble(5, row.predictedLogReturn());
            JdbcUtils.setNullableDouble(ps, 6, row.pUp());
            JdbcUtils.setNullableDouble(ps, 7, row.confidence());
            JdbcUtils.setNullableDouble(ps, 8, row.currentClose());
            JdbcUtils.setNullableDouble(ps, 9, row.predictedPrice());
            ps.setString(10, row.featuresJson());
        });
    }

    public List<PredictionRow> find(LocalDate asOf, int horizonDays, String sort, int limit) {
        String orderBy = switch (sort == null ? "" : sort.toLowerCase(Locale.ROOT)) {
            case "p_up_desc" -> "p_up DESC NULLS LAST, symbol";
            case "symbol_asc" -> "symbol ASC";
            default -> "predicted_log_return DESC, symbol";
        };
        String sql = SELECT + " WHERE as_of = ? AND horizon_days = ? ORDER BY " + orderBy + " LIMIT ?";
        return jdbc.query(sql, (rs, rowNum) -> mapRow(rs), Date.valueOf(asOf), horizonDays, limit);
    }

    public Optional<LocalDate> latestAsOf(int horizonDays) {
        return jdbc.query("SELECT max(as_of) AS as_of FROM predictions WHERE horizon_days = ?",
                rs -> rs.next() && rs.getDate("as_of") != null
                        ? Optional.of(rs.getDate("as_of").toLocalDate())
                        : Optional.empty(),
                horizonDays);
    }

    public Optional<LocalDate> latestAsOfForSymbol(String symbol) {
        return jdbc.query("SELECT max(as_of) AS as_of FROM predictions WHERE symbol = ?",
                rs -> rs.next() && rs.getDate("as_of") != null
                        ? Optional.of(rs.getDate("as_of").toLocalDate())
                        : Optional.empty(),
                symbol);
    }

    public List<PredictionRow> findBySymbolAtAsOf(String symbol, LocalDate asOf) {
        return jdbc.query(SELECT + " WHERE symbol = ? AND as_of = ? ORDER BY horizon_days",
                (rs, rowNum) -> mapRow(rs), symbol, Date.valueOf(asOf));
    }

    /** Marks a model version active for its horizon, deactivating older versions first. */
    public void activateModel(String version, ModelMetadata.HorizonModelMetadata meta,
                              String artifactUri, Instant trainedAt) {
        jdbc.update("UPDATE model_registry SET active = false WHERE horizon_days = ? AND version <> ?",
                meta.horizonDays(), version);
        Object directionAccuracy = meta.metrics() == null ? null : meta.metrics().get("directionAccuracy");
        Object rmse = meta.metrics() == null ? null : meta.metrics().get("rmse");
        jdbc.update("""
                INSERT INTO model_registry (version, horizon_days, trained_at, cv_direction_accuracy,
                                            cv_rmse, feature_list, artifact_uri, metadata, active)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, true)
                ON CONFLICT (version) DO UPDATE SET
                    trained_at = EXCLUDED.trained_at,
                    cv_direction_accuracy = EXCLUDED.cv_direction_accuracy,
                    cv_rmse = EXCLUDED.cv_rmse,
                    feature_list = EXCLUDED.feature_list,
                    artifact_uri = EXCLUDED.artifact_uri,
                    metadata = EXCLUDED.metadata,
                    active = true
                """,
                version,
                meta.horizonDays(),
                java.sql.Timestamp.from(trainedAt),
                directionAccuracy == null ? null : ((Number) directionAccuracy).doubleValue(),
                rmse == null ? null : ((Number) rmse).doubleValue(),
                toJsonArray(meta.pooledFeatureList()),
                artifactUri,
                toMetadataJson(meta));
    }

    /** Stores the horizon metadata that produced the prediction (L9). */
    private String toMetadataJson(ModelMetadata.HorizonModelMetadata meta) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("horizonDays", meta.horizonDays());
        metadata.put("target", meta.target());
        metadata.put("pooledFeatureList", meta.pooledFeatureList());
        metadata.put("perStockFeatureList", meta.perStockFeatureList());
        metadata.put("perStockCount", meta.perStock() == null ? 0 : meta.perStock().size());
        metadata.put("metrics", meta.metrics() == null ? Map.of() : meta.metrics());
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private static PredictionRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PredictionRow(
                rs.getString("symbol"),
                rs.getDate("as_of").toLocalDate(),
                rs.getInt("horizon_days"),
                rs.getDouble("predicted_log_return"),
                JdbcUtils.nullableDouble(rs, "p_up"),
                JdbcUtils.nullableDouble(rs, "confidence"),
                JdbcUtils.nullableDouble(rs, "current_close"),
                JdbcUtils.nullableDouble(rs, "predicted_price"),
                rs.getString("features"),
                rs.getString("model_version"));
    }

    private static String toJsonArray(List<String> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append('"').append(values.get(i).replace("\"", "\\\"")).append('"');
        }
        return builder.append(']').toString();
    }
}
