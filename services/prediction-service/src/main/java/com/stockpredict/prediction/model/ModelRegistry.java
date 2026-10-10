package com.stockpredict.prediction.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stockpredict.prediction.config.PredictionProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import ml.dmlc.xgboost4j.java.Booster;
import ml.dmlc.xgboost4j.java.DMatrix;
import ml.dmlc.xgboost4j.java.XGBoost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Loads XGBoost JSON models exported by A9 and serves them for batch inference.
 * Pooled models are loaded eagerly (a handful); per-stock models lazily into a
 * bounded Caffeine cache, so API pods can load none and batch pods load all
 * (plan risk mitigation: ~1,500 model files must not slow startup).
 */
@Component
public class ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    private final PredictionProperties properties;
    private final ObjectMapper objectMapper;
    private final Cache<String, Booster> perStockCache;
    /** Replaced atomically on reload so readers never see an empty registry. */
    private volatile Map<Integer, Booster> pooledModels = Map.of();

    private volatile ModelMetadata metadata;
    private volatile Path versionDir;

    public ModelRegistry(PredictionProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.perStockCache = Caffeine.newBuilder().maximumSize(2_000).build();
    }

    @PostConstruct
    public void load() {
        try {
            reload();
        } catch (RuntimeException ex) {
            log.warn("Model registry not loaded: {}", ex.getMessage());
        }
    }

    /** (Re)loads manifest + metadata + pooled models from the configured directory. */
    public synchronized void reload() {
        Path modelDir = Path.of(properties.getModelDir());
        if (!Files.isDirectory(modelDir)) {
            log.warn("Model directory {} does not exist; predictions require a batch job with models", modelDir);
            return;
        }
        Path manifestPath = modelDir.resolve("manifest.json");
        Path metadataPath;
        if (Files.exists(manifestPath)) {
            try (InputStream in = Files.newInputStream(manifestPath)) {
                var manifest = objectMapper.readTree(in);
                String activeVersion = manifest.path("activeVersion").asText(null);
                if (activeVersion == null || activeVersion.isBlank()) {
                    throw new IllegalStateException("manifest.json has no activeVersion");
                }
                metadataPath = modelDir.resolve(activeVersion).resolve("metadata.json");
            } catch (IOException ex) {
                throw new UncheckedIOException("Unable to read " + manifestPath, ex);
            }
        } else {
            metadataPath = modelDir.resolve("metadata.json");
        }
        if (!Files.exists(metadataPath)) {
            throw new IllegalStateException("Model metadata not found at " + metadataPath);
        }
        try (InputStream in = Files.newInputStream(metadataPath)) {
            ModelMetadata parsed = objectMapper.readValue(in, ModelMetadata.class);
            Path dir = metadataPath.getParent();
            Map<Integer, Booster> loaded = new LinkedHashMap<>();
            for (Map.Entry<Integer, ModelMetadata.HorizonModelMetadata> entry : parsed.horizons().entrySet()) {
                Path artifact = dir.resolve(entry.getValue().artifact());
                if (!Files.exists(artifact)) {
                    log.warn("Missing pooled artifact for horizon {}: {}", entry.getKey(), artifact);
                    continue;
                }
                loaded.put(entry.getKey(), loadBooster(artifact));
            }
            pooledModels = Map.copyOf(loaded);
            perStockCache.invalidateAll();
            this.metadata = parsed;
            this.versionDir = dir;
            log.info("Loaded model version {} with {} pooled horizons from {}",
                    parsed.version(), loaded.size(), dir);
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to read " + metadataPath, ex);
        }
    }

    public Optional<ModelMetadata> metadata() {
        return Optional.ofNullable(metadata);
    }

    public Optional<String> activeVersion() {
        return metadata().map(ModelMetadata::version);
    }

    public Optional<ModelMetadata.HorizonModelMetadata> horizon(int horizonDays) {
        return metadata().map(m -> m.horizons().get(horizonDays));
    }

    public Optional<Booster> pooled(int horizonDays) {
        return Optional.ofNullable(pooledModels.get(horizonDays));
    }

    public Optional<Booster> perStock(String symbol, int horizonDays) {
        ModelMetadata.HorizonModelMetadata horizon = horizon(horizonDays).orElse(null);
        Path dir = versionDir;
        if (horizon == null || dir == null) {
            return Optional.empty();
        }
        String relative = horizon.perStock().get(symbol);
        if (relative == null) {
            return Optional.empty();
        }
        String cacheKey = metadata.version() + ":" + horizonDays + ":" + symbol;
        Booster cached = perStockCache.get(cacheKey, key -> {
            Path artifact = dir.resolve(relative);
            if (!Files.exists(artifact)) {
                return null;
            }
            return loadBooster(artifact);
        });
        return Optional.ofNullable(cached);
    }

    /** Predict one row; synchronized because Booster inference is not re-entrant. */
    public double predict(Booster booster, float[] features) {
        return predictBatch(booster, new float[][] {features})[0];
    }

    /**
     * Predict many rows in one JNI call (XGBoost predicts row-wise), which turns
     * ~500 one-row matrices per horizon into a single matrix. Synchronized
     * because Booster inference is not re-entrant.
     */
    public double[] predictBatch(Booster booster, float[][] features) {
        if (features.length == 0) {
            return new double[0];
        }
        int rowCount = features.length;
        int columnCount = features[0].length;
        float[] flat = new float[rowCount * columnCount];
        for (int i = 0; i < rowCount; i++) {
            System.arraycopy(features[i], 0, flat, i * columnCount, columnCount);
        }
        float[][] result;
        synchronized (booster) {
            DMatrix matrix = null;
            try {
                matrix = new DMatrix(flat, rowCount, columnCount, Float.NaN);
                result = booster.predict(matrix);
            } catch (Exception ex) {
                throw new IllegalStateException("XGBoost prediction failed", ex);
            } finally {
                if (matrix != null) {
                    matrix.dispose();
                }
            }
        }
        double[] predictions = new double[result.length];
        for (int i = 0; i < result.length; i++) {
            predictions[i] = result[i][0];
        }
        return predictions;
    }

    public List<Integer> horizons() {
        return metadata().map(m -> m.horizons().keySet().stream().sorted().toList()).orElse(List.of());
    }

    private static Booster loadBooster(Path artifact) {
        try (InputStream in = Files.newInputStream(artifact)) {
            return XGBoost.loadModel(in);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to load XGBoost model " + artifact, ex);
        }
    }
}
