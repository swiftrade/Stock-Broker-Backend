package com.stockpredict.prediction.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpredict.common.error.ApiException;
import com.stockpredict.common.error.ErrorCode;
import com.stockpredict.common.time.BarTimestamps;
import com.stockpredict.common.time.MarketClock;
import com.stockpredict.feature.engine.Bar;
import com.stockpredict.feature.engine.EngineConfig;
import com.stockpredict.feature.engine.FeatureEngine;
import com.stockpredict.feature.engine.FeatureTable;
import com.stockpredict.prediction.config.PredictionProperties;
import com.stockpredict.prediction.data.BarRepository;
import com.stockpredict.prediction.data.PredictionRepository;
import com.stockpredict.prediction.data.PredictionRow;
import com.stockpredict.prediction.model.ModelMetadata;
import com.stockpredict.prediction.model.ModelRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ml.dmlc.xgboost4j.java.Booster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pre-market batch: build features as of the prior close, run the pooled model
 * (and per-stock models when present, 50/50 ensemble as in the Python pipeline)
 * and upsert rows into {@code predictions}. API calls never run inference.
 */
@Service
public class PredictionBatchService {

    private static final Logger log = LoggerFactory.getLogger(PredictionBatchService.class);

    private final BarRepository barRepository;
    private final PredictionRepository predictionRepository;
    private final ModelRegistry modelRegistry;
    private final PredictionProperties properties;
    private final EngineConfig engineConfig;
    private final MarketClock clock;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    public PredictionBatchService(BarRepository barRepository,
                                  PredictionRepository predictionRepository,
                                  ModelRegistry modelRegistry,
                                  PredictionProperties properties,
                                  EngineConfig engineConfig,
                                  MarketClock clock,
                                  ObjectMapper objectMapper,
                                  PlatformTransactionManager transactionManager,
                                  MeterRegistry meterRegistry) {
        this.barRepository = barRepository;
        this.predictionRepository = predictionRepository;
        this.modelRegistry = modelRegistry;
        this.properties = properties;
        this.engineConfig = engineConfig;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.meterRegistry = meterRegistry;
    }

    /**
     * Runs feature computation, inference and JSON serialization outside any
     * transaction; only the upsert/activate write phase is transactional.
     */
    public BatchResult run() {
        String version = modelRegistry.activeVersion()
                .orElseThrow(() -> new ApiException(ErrorCode.UNPROCESSABLE,
                        "No active model version; run ml-export and configure stockpredict.predictions.model-dir"));
        Instant latest = barRepository.latestTimestamp()
                .orElseThrow(() -> new ApiException(ErrorCode.UNPROCESSABLE,
                        "No bars available; market-data ingestion has not run"));
        LocalDate asOf = BarTimestamps.sessionDateOfDailyBar(latest);

        List<String> symbols = barRepository.listSymbols();
        Set<String> marketSymbols = new LinkedHashSet<>(properties.getMarketSymbols());
        marketSymbols.addAll(properties.getSectorEtfs());
        // Market/sector ETFs provide features; they are never prediction targets.
        symbols = symbols.stream().filter(symbol -> !marketSymbols.contains(symbol)).toList();
        if (symbols.isEmpty()) {
            throw new ApiException(ErrorCode.UNPROCESSABLE, "No symbols with bars available");
        }
        Instant from = latest.minus(Duration.ofDays(properties.getHistoryDays()));
        List<Bar> stockBars = barRepository.loadBars(symbols, from, latest);
        List<Bar> marketBars = barRepository.loadBars(marketSymbols, from, latest);

        FeatureTable table = new FeatureEngine(engineConfig).compute(stockBars, marketBars);
        Map<String, Integer> latestRowBySymbol = latestRows(table);

        List<PredictionRow> rows = new ArrayList<>();
        List<ModelMetadata.HorizonModelMetadata> activated = new ArrayList<>();
        List<Map.Entry<String, Integer>> entries = List.copyOf(latestRowBySymbol.entrySet());
        for (int horizon : modelRegistry.horizons()) {
            ModelMetadata.HorizonModelMetadata meta = modelRegistry.horizon(horizon).orElse(null);
            Booster pooled = modelRegistry.pooled(horizon).orElse(null);
            if (meta == null || pooled == null || meta.pooledFeatureList().isEmpty()) {
                continue;
            }
            int before = rows.size();
            // One matrix per horizon instead of one per symbol.
            float[][] features = new float[entries.size()][];
            int validCount = 0;
            for (Map.Entry<String, Integer> entry : entries) {
                if (hasUsableClose(table, entry.getValue())) {
                    features[validCount++] = vector(table, entry.getValue(), meta.pooledFeatureList());
                }
            }
            float[][] matrix = Arrays.copyOf(features, validCount);
            double[] pooledPredictions = modelRegistry.predictBatch(pooled, matrix);
            int prediction = 0;
            for (Map.Entry<String, Integer> entry : entries) {
                if (!hasUsableClose(table, entry.getValue())) {
                    continue;
                }
                PredictionRow row = predictSymbol(entry.getKey(), entry.getValue(), asOf, version, meta,
                        pooledPredictions[prediction++], table);
                if (row != null) {
                    rows.add(row);
                }
            }
            activated.add(meta);
            meterRegistry.counter("stockpredict.predictions.rows", "horizon", String.valueOf(horizon))
                    .increment(rows.size() - before);
            log.info("Horizon {}d: {} predictions", horizon, rows.size() - before);
        }
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.UNPROCESSABLE,
                    "No predictions produced; check model feature lists against available features");
        }

        // Short write transaction: all inference and JSON work is already done.
        transactionTemplate.executeWithoutResult(status -> {
            predictionRepository.upsertAll(rows);
            Instant trainedAt = clock.now();
            for (ModelMetadata.HorizonModelMetadata meta : activated) {
                predictionRepository.activateModel(version, meta, meta.artifact(), trainedAt);
            }
        });
        List<Integer> horizons = activated.stream()
                .map(ModelMetadata.HorizonModelMetadata::horizonDays)
                .sorted()
                .toList();
        log.info("Batch complete: version={} asOf={} rows={} horizons={}", version, asOf, rows.size(), horizons);
        return new BatchResult(asOf, version, rows.size(), horizons);
    }

    private static boolean hasUsableClose(FeatureTable table, int row) {
        Double close = table.value(row, "close");
        return close != null && !close.isNaN() && close > 0;
    }

    private PredictionRow predictSymbol(String symbol, int row, LocalDate asOf, String version,
                                        ModelMetadata.HorizonModelMetadata meta, double pooledPrediction,
                                        FeatureTable table) {
        Double close = table.value(row, "close");
        if (close == null || close.isNaN() || close <= 0) {
            return null;
        }

        Double perStockPrediction = null;
        if (properties.isIncludePerStock() && meta.perStock().containsKey(symbol)) {
            Booster perStock = modelRegistry.perStock(symbol, meta.horizonDays()).orElse(null);
            if (perStock != null) {
                perStockPrediction = modelRegistry.predict(perStock,
                        vectorWithPooled(table, row, meta.perStockFeatureList(), pooledPrediction));
            }
        }
        double ensemble = perStockPrediction == null
                ? pooledPrediction
                : 0.5 * pooledPrediction + 0.5 * perStockPrediction;
        // The model predicts EXCESS return (stock minus SPY), so this price
        // embeds a "SPY flat" assumption; see the contract description (M15).
        double predictedPrice = close * Math.exp(ensemble);
        return new PredictionRow(symbol, asOf, meta.horizonDays(), ensemble, null, null,
                close, predictedPrice, featuresJson(table, row), version);
    }

    private static Map<String, Integer> latestRows(FeatureTable table) {
        Map<String, Integer> latest = new LinkedHashMap<>();
        for (int row = 0; row < table.size(); row++) {
            String symbol = table.symbol(row);
            Integer current = latest.get(symbol);
            if (current == null || table.timestamp(row).isAfter(table.timestamp(current))) {
                latest.put(symbol, row);
            }
        }
        return latest;
    }

    private static float[] vector(FeatureTable table, int row, List<String> featureList) {
        float[] values = new float[featureList.size()];
        for (int i = 0; i < featureList.size(); i++) {
            values[i] = toFloat(table.valueOrNaN(row, featureList.get(i)));
        }
        return values;
    }

    private static float[] vectorWithPooled(FeatureTable table, int row, List<String> featureList,
                                            double pooledPrediction) {
        float[] values = new float[featureList.size()];
        for (int i = 0; i < featureList.size(); i++) {
            String feature = featureList.get(i);
            values[i] = "pooled_pred".equals(feature)
                    ? (float) pooledPrediction
                    : toFloat(table.valueOrNaN(row, feature));
        }
        return values;
    }

    private static float toFloat(double value) {
        return Double.isNaN(value) ? Float.NaN : (float) value;
    }

    private String featuresJson(FeatureTable table, int row) {
        Map<String, Object> features = new LinkedHashMap<>();
        for (String column : table.columns()) {
            Double value = table.value(row, column);
            features.put(column, value == null || value.isNaN() ? null : value);
        }
        try {
            return objectMapper.writeValueAsString(features);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize features", ex);
        }
    }
}
