package com.stockpredict.prediction.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * Parsed {@code metadata.json} written by {@code ml-export/export_models.py}.
 * Feature lists are authoritative: the Java side never hard-codes feature order.
 */
public record ModelMetadata(String version, String exportedAt,
                            Map<Integer, HorizonModelMetadata> horizons) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HorizonModelMetadata(
            int horizonDays,
            String target,
            List<String> pooledFeatureList,
            List<String> perStockFeatureList,
            String artifact,
            Map<String, Object> metrics,
            Map<String, String> perStock) {

        public List<String> pooledFeatureList() {
            return pooledFeatureList == null ? List.of() : pooledFeatureList;
        }

        public List<String> perStockFeatureList() {
            return perStockFeatureList == null ? pooledFeatureList() : perStockFeatureList;
        }

        public Map<String, String> perStock() {
            return perStock == null ? Map.of() : perStock;
        }

        /** Typed metric accessor; metrics are untyped JSON from the exporter. */
        public Double metric(String name) {
            Object value = metrics == null ? null : metrics.get(name);
            return value instanceof Number number ? number.doubleValue() : null;
        }

        public Integer metricAsInt(String name) {
            Double value = metric(name);
            return value == null ? null : (int) (double) value;
        }
    }
}
