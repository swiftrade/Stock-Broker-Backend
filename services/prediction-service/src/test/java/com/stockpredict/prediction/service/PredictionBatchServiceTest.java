package com.stockpredict.prediction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpredict.common.time.BarTimestamps;
import com.stockpredict.common.time.MarketClock;
import com.stockpredict.feature.engine.Bar;
import com.stockpredict.feature.engine.EngineConfig;
import com.stockpredict.prediction.config.PredictionProperties;
import com.stockpredict.prediction.data.BarRepository;
import com.stockpredict.prediction.data.PredictionRepository;
import com.stockpredict.prediction.data.PredictionRow;
import com.stockpredict.prediction.model.ModelRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import ml.dmlc.xgboost4j.java.Booster;
import ml.dmlc.xgboost4j.java.DMatrix;
import ml.dmlc.xgboost4j.java.XGBoost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class PredictionBatchServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T21:00:00Z");
    private static final int DAYS = 70;

    @TempDir
    Path tempDir;

    private BarRepository barRepository;
    private PredictionRepository predictionRepository;
    private ModelRegistry registry;
    private PredictionBatchService service;
    private List<Bar> aaaBars;
    private List<Bar> spyBars;

    private static List<Bar> bars(String symbol, double base, double drift) {
        List<Bar> bars = new ArrayList<>(DAYS);
        LocalDate firstSession = T0.atZone(ZoneOffset.UTC).toLocalDate();
        for (int i = 0; i < DAYS; i++) {
            double close = base + drift * i + 0.5 * Math.sin(i);
            // Daily bars follow the storage convention: 00:00Z of the session date.
            bars.add(new Bar(symbol, BarTimestamps.dailyBarTimestamp(firstSession.plusDays(i)),
                    close - 0.2, close + 0.5, close - 0.5, close, 1_000_000 + i * 1000.0));
        }
        return bars;
    }

    private void writeModel() throws Exception {
        Path versionDir = tempDir.resolve("test-1.0.0");
        Files.createDirectories(versionDir);
        float[][] rows = {{0, 1}, {1, 2}, {2, 3}, {3, 4}, {4, 5}, {5, 6}};
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
        Booster booster = XGBoost.train(train, params, 10, new HashMap<>(), null, null);
        booster.saveModel(versionDir.resolve("pooled_1d.json").toString());
        train.dispose();
        booster.dispose();

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
                      "metrics": {"directionAccuracy": 0.52},
                      "perStock": {}
                    }
                  }
                }
                """);
        Files.writeString(tempDir.resolve("manifest.json"), """
                {"activeVersion": "test-1.0.0"}
                """);
    }

    @BeforeEach
    void setUp() throws Exception {
        writeModel();
        aaaBars = bars("AAA", 100, 0.3);
        spyBars = bars("SPY", 400, 0.2);

        barRepository = mock(BarRepository.class);
        predictionRepository = mock(PredictionRepository.class);
        PredictionProperties properties = new PredictionProperties();
        properties.setModelDir(tempDir.toString());
        properties.setIncludePerStock(false);
        registry = new ModelRegistry(properties, new ObjectMapper());
        registry.load();

        when(barRepository.latestTimestamp()).thenReturn(java.util.Optional.of(aaaBars.get(DAYS - 1).timestamp()));
        when(barRepository.listSymbols()).thenReturn(List.of("AAA"));
        when(barRepository.loadBars(anyCollection(), any(), any())).thenAnswer(invocation -> {
            java.util.Collection<String> symbols = invocation.getArgument(0);
            if (symbols.contains("AAA")) {
                return aaaBars;
            }
            if (symbols.contains("SPY")) {
                return spyBars;
            }
            return List.of();
        });

        EngineConfig engineConfig = EngineConfig.builder()
                .horizons(List.of(1))
                .predictionDelay(1)
                .marketSymbols(List.of("SPY"))
                .sectorEtfs(List.of())
                .residualizeFeatures(false)
                .useExcessReturnTarget(false)
                .build();
        MarketClock clock = new MarketClock(Clock.fixed(
                Instant.parse("2026-03-12T12:00:00Z"), ZoneOffset.UTC));
        service = new PredictionBatchService(barRepository, predictionRepository, registry,
                properties, engineConfig, clock, new ObjectMapper(),
                mock(org.springframework.transaction.PlatformTransactionManager.class),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Test
    void batchProducesOnePredictionPerSymbolAndHorizon() {
        BatchResult result = service.run();

        assertThat(result.predictionCount()).isEqualTo(1);
        assertThat(result.horizons()).containsExactly(1);
        assertThat(result.modelVersion()).isEqualTo("test-1.0.0");
        assertThat(result.asOf()).isEqualTo(LocalDate.of(2026, 3, 11));

        ArgumentCaptor<List<PredictionRow>> rows = ArgumentCaptor.forClass(List.class);
        verify(predictionRepository).upsertAll(rows.capture());
        PredictionRow row = rows.getValue().get(0);
        assertThat(row.symbol()).isEqualTo("AAA");
        assertThat(row.horizonDays()).isEqualTo(1);
        assertThat(row.currentClose()).isEqualTo(aaaBars.get(DAYS - 1).close());
        assertThat(row.predictedPrice()).isNotNull();
        assertThat(row.featuresJson()).contains("log_return_1d");
        assertThat(row.modelVersion()).isEqualTo("test-1.0.0");

        verify(predictionRepository).activateModel(eq("test-1.0.0"), any(), eq("pooled_1d.json"), any());
    }

    @Test
    void emptyFeatureListIsSkippedAndFailsWhenNothingProduces() throws Exception {
        // Overwrite metadata with an empty feature list.
        Files.writeString(tempDir.resolve("test-1.0.0").resolve("metadata.json"), """
                {"version":"test-1.0.0","exportedAt":"2026-01-01T00:00:00Z",
                 "horizons":{"1":{"horizonDays":1,"target":"t","pooledFeatureList":[],
                 "perStockFeatureList":[],"artifact":"pooled_1d.json","metrics":{},"perStock":{}}}}
                """);
        registry.reload();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.run())
                .isInstanceOf(com.stockpredict.common.error.ApiException.class);
    }
}
