package com.stockpredict.prediction.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpredict.common.error.ApiException;
import com.stockpredict.common.plan.Plan;
import com.stockpredict.common.time.MarketClock;
import com.stockpredict.common.time.TradingCalendar;
import com.stockpredict.prediction.api.model.HorizonMetrics;
import com.stockpredict.prediction.api.model.HorizonPrediction;
import com.stockpredict.prediction.api.model.ModelInfo;
import com.stockpredict.prediction.api.model.Prediction;
import com.stockpredict.prediction.api.model.PredictionList;
import com.stockpredict.prediction.api.model.SymbolPrediction;
import com.stockpredict.prediction.config.PredictionProperties;
import com.stockpredict.prediction.data.PredictionRepository;
import com.stockpredict.prediction.data.PredictionRow;
import com.stockpredict.prediction.model.ModelMetadata;
import com.stockpredict.prediction.model.ModelRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read path for predictions. Served entirely from Postgres (never inline
 * inference); the FREE plan is capped server-side at the configured top-N.
 */
@Service
public class PredictionQueryService {

    private static final Logger log = LoggerFactory.getLogger(PredictionQueryService.class);

    private final PredictionRepository predictionRepository;
    private final ModelRegistry modelRegistry;
    private final PredictionProperties properties;
    private final TradingCalendar tradingCalendar;
    private final MarketClock clock;
    private final ObjectMapper objectMapper;

    public PredictionQueryService(PredictionRepository predictionRepository,
                                  ModelRegistry modelRegistry,
                                  PredictionProperties properties,
                                  TradingCalendar tradingCalendar,
                                  MarketClock clock,
                                  ObjectMapper objectMapper) {
        this.predictionRepository = predictionRepository;
        this.modelRegistry = modelRegistry;
        this.properties = properties;
        this.tradingCalendar = tradingCalendar;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PredictionList getIntradayPredictions(LocalDate date, Integer limit, String sort, Plan plan) {
        String fallbackVersion = modelRegistry.activeVersion().orElse("unknown");
        LocalDate asOf = date != null ? date : predictionRepository.latestAsOf(1).orElse(null);
        if (asOf == null) {
            return new PredictionList()
                    .modelVersion(fallbackVersion)
                    .stale(true)
                    .disclaimer(properties.getDisclaimer())
                    .predictions(List.of());
        }

        int effectiveLimit = limit == null ? 100 : Math.min(Math.max(limit, 1), 500);
        if (plan == Plan.FREE) {
            effectiveLimit = Math.min(effectiveLimit, properties.getFreePlanLimit());
        }
        List<PredictionRow> rows = predictionRepository.find(asOf, 1, sort, effectiveLimit);

        List<Prediction> predictions = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            predictions.add(toPrediction(rows.get(i), i + 1));
        }
        String version = rows.isEmpty() ? fallbackVersion : rows.get(0).modelVersion();
        return new PredictionList()
                .asOf(asOf)
                .modelVersion(version)
                .stale(isStale(asOf))
                .disclaimer(properties.getDisclaimer())
                .predictions(predictions);
    }

    @Transactional(readOnly = true)
    public SymbolPrediction getSymbolPrediction(String symbol) {
        LocalDate asOf = predictionRepository.latestAsOfForSymbol(symbol)
                .orElseThrow(() -> ApiException.notFound("Predictions for " + symbol));
        List<PredictionRow> rows = predictionRepository.findBySymbolAtAsOf(symbol, asOf);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Predictions for " + symbol);
        }
        List<HorizonPrediction> horizons = rows.stream()
                .sorted((a, b) -> Integer.compare(a.horizonDays(), b.horizonDays()))
                .map(row -> new HorizonPrediction()
                        .horizonDays(row.horizonDays())
                        .predictedLogReturn(row.predictedLogReturn())
                        .predictedPctChange((Math.exp(row.predictedLogReturn()) - 1) * 100)
                        .predictedPrice(row.predictedPrice()))
                .toList();
        return new SymbolPrediction()
                .symbol(symbol)
                .asOf(asOf)
                .modelVersion(rows.get(0).modelVersion())
                .stale(isStale(asOf))
                .disclaimer(properties.getDisclaimer())
                .horizons(horizons)
                .features(parseFeatures(rows.get(0).featuresJson()));
    }

    @Transactional(readOnly = true)
    public ModelInfo getModelInfo() {
        ModelMetadata metadata = modelRegistry.metadata().orElse(null);
        if (metadata == null) {
            return new ModelInfo()
                    .version("unknown")
                    .trainedAt(null)
                    .horizons(List.of())
                    .disclaimer(properties.getDisclaimer());
        }
        List<HorizonMetrics> horizons = new ArrayList<>();
        for (ModelMetadata.HorizonModelMetadata horizon : metadata.horizons().values()) {
            horizons.add(new HorizonMetrics()
                    .horizonDays(horizon.horizonDays())
                    .directionAccuracy(horizon.metric("directionAccuracy"))
                    .rmse(horizon.metric("rmse"))
                    .cvFolds(horizon.metricAsInt("cvFolds")));
        }
        return new ModelInfo()
                .version(metadata.version())
                .trainedAt(parseInstant(metadata.exportedAt()))
                .horizons(horizons)
                .disclaimer(properties.getDisclaimer());
    }

    private boolean isStale(LocalDate asOf) {
        LocalDate latestExpected = tradingCalendar.previousTradingDay(clock.todayEt());
        return asOf.isBefore(latestExpected);
    }

    private static Prediction toPrediction(PredictionRow row, int rank) {
        Double close = row.currentClose();
        Double pctChange = row.predictedPrice() == null || close == null || close == 0
                ? null
                : (row.predictedPrice() / close - 1) * 100;
        return new Prediction()
                .symbol(row.symbol())
                .rank(rank)
                .predictedLogReturn(row.predictedLogReturn())
                .predictedPctChange(pctChange)
                .pUp(row.pUp())
                .confidence(row.confidence())
                .currentClose(close)
                .predictedPrice(row.predictedPrice());
    }

    private Map<String, Object> parseFeatures(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception ex) {
            log.warn("Unable to parse stored features: {}", ex.getMessage());
            return Map.of();
        }
    }

    private static java.time.OffsetDateTime parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value).atOffset(ZoneOffset.UTC);
        } catch (Exception ex) {
            return null;
        }
    }
}
