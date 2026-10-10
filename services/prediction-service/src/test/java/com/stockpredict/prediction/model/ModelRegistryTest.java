package com.stockpredict.prediction.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpredict.prediction.config.PredictionProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import ml.dmlc.xgboost4j.java.Booster;
import ml.dmlc.xgboost4j.java.DMatrix;
import ml.dmlc.xgboost4j.java.XGBoost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelRegistryTest {

    @TempDir
    Path tempDir;

    private static void writeTinyModel(Path path) throws Exception {
        float[][] rows = {{0, 0}, {1, 1}, {2, 2}, {3, 3}, {4, 4}, {5, 5}};
        float[] labels = {0, 1, 2, 3, 4, 5};
        int n = rows.length;
        int m = rows[0].length;
        float[] flat = new float[n * m];
        for (int i = 0; i < n; i++) {
            System.arraycopy(rows[i], 0, flat, i * m, m);
        }
        DMatrix train = new DMatrix(flat, n, m, Float.NaN);
        train.setLabel(labels);
        Map<String, Object> params = new HashMap<>();
        params.put("max_depth", 2);
        params.put("eta", 1.0);
        params.put("objective", "reg:squarederror");
        params.put("tree_method", "hist");
        params.put("seed", 42);
        Booster booster = XGBoost.train(train, params, 10, new HashMap<>(), null, null);
        booster.saveModel(path.toString());
        train.dispose();
        booster.dispose();
    }

    private ModelRegistry registry() throws Exception {
        Path versionDir = tempDir.resolve("test-1.0.0");
        Files.createDirectories(versionDir);
        writeTinyModel(versionDir.resolve("pooled_1d.json"));
        Files.writeString(versionDir.resolve("metadata.json"), """
                {
                  "version": "test-1.0.0",
                  "exportedAt": "2026-01-01T00:00:00Z",
                  "horizons": {
                    "1": {
                      "horizonDays": 1,
                      "target": "target_return_1d",
                      "pooledFeatureList": ["log_return_1d", "ma_ratio_5d"],
                      "perStockFeatureList": ["log_return_1d", "ma_ratio_5d", "pooled_pred"],
                      "artifact": "pooled_1d.json",
                      "metrics": {"directionAccuracy": 0.52, "rmse": 0.01},
                      "perStock": {"AAA": "per_stock/AAA_1d.json"}
                    }
                  }
                }
                """);
        Files.writeString(tempDir.resolve("manifest.json"), """
                {"activeVersion": "test-1.0.0", "versions": ["test-1.0.0"]}
                """);
        PredictionProperties properties = new PredictionProperties();
        properties.setModelDir(tempDir.toString());
        ModelRegistry registry = new ModelRegistry(properties, new ObjectMapper());
        registry.load();
        return registry;
    }

    @Test
    void loadsManifestMetadataAndPooledModel() throws Exception {
        ModelRegistry registry = registry();

        assertThat(registry.activeVersion()).contains("test-1.0.0");
        assertThat(registry.horizons()).containsExactly(1);
        assertThat(registry.horizon(1)).isPresent();
        assertThat(registry.horizon(1).orElseThrow().pooledFeatureList())
                .containsExactly("log_return_1d", "ma_ratio_5d");
        assertThat(registry.pooled(1)).isPresent();
    }

    @Test
    void predictsWithLoadedModel() throws Exception {
        ModelRegistry registry = registry();
        Booster booster = registry.pooled(1).orElseThrow();

        double prediction = registry.predict(booster, new float[] {4f, 4f});

        assertThat(prediction).isBetween(3.0, 5.0);
    }

    @Test
    void missingModelDirectoryLeavesRegistryEmpty() {
        PredictionProperties properties = new PredictionProperties();
        properties.setModelDir(tempDir.resolve("does-not-exist").toString());
        ModelRegistry registry = new ModelRegistry(properties, new ObjectMapper());
        registry.load();

        assertThat(registry.activeVersion()).isEmpty();
        assertThat(registry.pooled(1)).isEmpty();
    }

    @Test
    void missingPerStockArtifactReturnsEmpty() throws Exception {
        ModelRegistry registry = registry();
        assertThat(registry.perStock("AAA", 1)).isEmpty();
        assertThat(registry.perStock("ZZZ", 1)).isEmpty();
    }
}
