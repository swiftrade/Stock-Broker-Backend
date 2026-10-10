package com.stockpredict.feature.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeatureEngineTest {

    private static final Instant T0 = Instant.parse("2026-01-01T21:00:00Z");
    private static final int DAYS = 60;

    private static List<Bar> bars(String symbol, double base, double drift) {
        List<Bar> bars = new ArrayList<>(DAYS);
        for (int i = 0; i < DAYS; i++) {
            double close = base + drift * i + 0.5 * Math.sin(i);
            bars.add(new Bar(symbol, T0.plus(Duration.ofDays(i)),
                    close - 0.2, close + 0.5, close - 0.5, close, 1_000_000 + i * 1000.0));
        }
        return bars;
    }

    private static List<Bar> allBars() {
        List<Bar> bars = new ArrayList<>();
        bars.addAll(bars("AAA", 100, 0.3));
        bars.addAll(bars("BBB", 50, -0.1));
        bars.addAll(bars("CCC", 200, 0.05));
        return bars;
    }

    private static List<Bar> spyBars() {
        return bars("SPY", 400, 0.2);
    }

    private static int rowOf(FeatureTable table, String symbol, int day) {
        Instant ts = T0.plus(Duration.ofDays(day));
        for (int row = 0; row < table.size(); row++) {
            if (table.symbol(row).equals(symbol) && table.timestamp(row).equals(ts)) {
                return row;
            }
        }
        throw new AssertionError("Row not found for " + symbol + " day " + day);
    }

    private static double closeOf(String symbol, int day) {
        List<Bar> bars = symbol.equals("SPY") ? spyBars() : bars(symbol, symbol.equals("AAA") ? 100 : 0, 0);
        return bars.get(day).close();
    }

    private static EngineConfig.Builder baseConfig() {
        return EngineConfig.builder()
                .horizons(List.of(1, 5, 20))
                .marketSymbols(List.of("SPY"))
                .sectorEtfs(List.of())
                .predictionDelay(0)
                .residualizeFeatures(false)
                .useExcessReturnTarget(false);
    }

    @Test
    void producesExpectedColumns() {
        FeatureTable table = new FeatureEngine(baseConfig().build()).compute(allBars(), spyBars());
        List<String> columns = table.columns();
        assertThat(columns).contains(
                "log_return_1d", "log_return_2d", "log_return_5d", "log_return_10d", "log_return_20d",
                "ma_ratio_5d", "ma_ratio_10d", "ma_ratio_20d", "ma_ratio_50d",
                "volatility_20d", "volume_change_1d", "volume_ma_20d", "volume_ratio_20d",
                "rsi_14", "macd_line", "macd_signal", "macd_histogram",
                "bb_upper", "bb_middle", "bb_lower", "bb_width", "bb_position",
                "true_range", "atr_14", "atr_14_ratio",
                "target_return_1d", "target_price_1d", "target_o2c_1d",
                "market_SPY_log_return_1d", "market_SPY_log_return_5d",
                "vol_SPY_volatility_20d",
                "log_return_20d_rank", "volatility_20d_rank", "rsi_14_rank");
        assertThat(columns).doesNotContain("target_return_20d_rank");
    }

    @Test
    void targetReturnAndPriceMatchForwardClose() {
        FeatureTable table = new FeatureEngine(baseConfig().build()).compute(allBars(), spyBars());
        int row = rowOf(table, "AAA", 0);
        List<Bar> aaa = bars("AAA", 100, 0.3);
        double expected = Math.log(aaa.get(1).close() / aaa.get(0).close());
        assertThat(table.valueOrNaN(row, "target_return_1d")).isCloseTo(expected, within(1e-12));
        assertThat(table.valueOrNaN(row, "target_price_1d")).isCloseTo(aaa.get(1).close(), within(1e-12));
    }

    @Test
    void excessTargetSubtractsSpyForwardReturn() {
        FeatureTable table = new FeatureEngine(baseConfig().useExcessReturnTarget(true).build())
                .compute(allBars(), spyBars());
        List<Bar> aaa = bars("AAA", 100, 0.3);
        List<Bar> spy = spyBars();
        double stock = Math.log(aaa.get(1).close() / aaa.get(0).close());
        double market = Math.log(spy.get(1).close() / spy.get(0).close());
        assertThat(table.valueOrNaN(rowOf(table, "AAA", 0), "target_return_1d"))
                .isCloseTo(stock - market, within(1e-12));
    }

    @Test
    void executionDelayShiftsFeaturesButNotTargets() {
        FeatureTable table = new FeatureEngine(baseConfig().predictionDelay(1).build())
                .compute(allBars(), spyBars());
        List<Bar> aaa = bars("AAA", 100, 0.3);
        int row = rowOf(table, "AAA", 2);
        double expectedShifted = Math.log(aaa.get(1).close() / aaa.get(0).close());
        assertThat(table.valueOrNaN(row, "log_return_1d")).isCloseTo(expectedShifted, within(1e-12));

        // Targets are never shifted.
        int targetRow = rowOf(table, "AAA", 0);
        double expectedTarget = Math.log(aaa.get(1).close() / aaa.get(0).close());
        assertThat(table.valueOrNaN(targetRow, "target_return_1d")).isCloseTo(expectedTarget, within(1e-12));

        // First delayed row has no history.
        assertThat(table.valueOrNaN(rowOf(table, "AAA", 0), "log_return_1d")).isNaN();
    }

    @Test
    void residualizedReturnIsStockMinusSpy() {
        FeatureTable table = new FeatureEngine(baseConfig().residualizeFeatures(true).build())
                .compute(allBars(), spyBars());
        int row = rowOf(table, "AAA", 5);
        double stock = table.valueOrNaN(row, "log_return_5d");
        double market = table.valueOrNaN(row, "market_SPY_log_return_5d");
        assertThat(table.valueOrNaN(row, "resid_return_5d")).isCloseTo(stock - market, within(1e-12));
    }

    @Test
    void crossSectionalRankSpansZeroToOne() {
        FeatureTable table = new FeatureEngine(baseConfig().build()).compute(allBars(), spyBars());
        int row = rowOf(table, "AAA", 30);
        double rank = table.valueOrNaN(row, "log_return_20d_rank");
        assertThat(rank).isBetween(0.0, 1.0);
        // Two distinct values across the universe -> {0.5, 1.0}
        long distinct = List.of("AAA", "BBB", "CCC").stream()
                .map(symbol -> table.valueOrNaN(rowOf(table, symbol, 30), "log_return_20d_rank"))
                .distinct()
                .count();
        assertThat(distinct).isEqualTo(3);
    }

    @Test
    void featureColumnSelectionMatchesPythonExclusions() {
        List<String> columns = List.of("symbol", "timestamp", "close", "volume", "volume_ma_20d",
                "true_range", "sector_label", "target_return_1d", "market_cap", "log_market_cap",
                "log_return_1d", "rsi_14", "sector_Technology");
        assertThat(FeatureEngine.getFeatureColumns(columns))
                .containsExactly("log_market_cap", "log_return_1d", "rsi_14", "sector_Technology");
    }

    @Test
    void sectorDummiesAndSectorRanksAreProduced() {
        EngineConfig config = baseConfig()
                .sectorBySymbol(java.util.Map.of("AAA", "Tech", "BBB", "Health"))
                .build();
        FeatureTable table = new FeatureEngine(config).compute(allBars(), spyBars());
        int row = rowOf(table, "AAA", 30);
        assertThat(table.valueOrNaN(row, "sector_Tech")).isEqualTo(1.0);
        assertThat(table.valueOrNaN(row, "sector_Health")).isEqualTo(0.0);
        assertThat(table.columns()).contains("log_return_20d_sector_rank");
    }
}
