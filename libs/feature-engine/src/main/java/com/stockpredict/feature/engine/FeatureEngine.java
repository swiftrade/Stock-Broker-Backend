package com.stockpredict.feature.engine;

import com.stockpredict.feature.engine.indicators.TechnicalIndicators;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pure-Java port of {@code STOCKMARKETANALYSIS/features.py}
 * ({@code build_feature_dataset}): technical, market/sector, residualized and
 * cross-sectional features, the excess-return target and the execution delay.
 *
 * <p>Column names and semantics match Python so the golden parity test can
 * compare values at 1e-9. Feature <em>order</em> for serving comes from the
 * model metadata, never from this class.
 */
public final class FeatureEngine {

    private static final Set<String> EXCLUDED_COLUMNS = Set.of(
            "symbol", "timestamp", "open", "high", "low", "close", "volume",
            "vwap", "trade_count", "volume_ma_20d", "sector_label", "true_range");

    private static final int[] RETURN_WINDOWS = {1, 2, 5, 10, 20};
    private static final int[] MA_WINDOWS = {5, 10, 20, 50};
    private static final int[] MARKET_RETURN_WINDOWS = {1, 5, 10, 20};
    private static final int[] RESIDUAL_WINDOWS = {1, 5, 10, 20};

    private final EngineConfig config;

    public FeatureEngine(EngineConfig config) {
        this.config = config;
    }

    public FeatureTable compute(List<Bar> stockBars, List<Bar> marketBars) {
        Map<String, List<Bar>> bySymbol = groupBySymbol(stockBars);
        Map<String, List<Bar>> marketBySymbol = groupBySymbol(marketBars);

        FeatureTable table = new FeatureTable();
        Map<String, List<Integer>> rowIndicesBySymbol = new LinkedHashMap<>();

        for (Map.Entry<String, List<Bar>> entry : bySymbol.entrySet()) {
            List<Integer> indices = computeSymbol(entry.getKey(), entry.getValue(), table);
            rowIndicesBySymbol.put(entry.getKey(), indices);
        }

        mergeMarketFeatures(table, marketBySymbol);
        residualize(table);
        applyExcessReturnTarget(table, marketBySymbol);
        applySectorDummies(table, rowIndicesBySymbol);
        addCrossSectionalRanks(table, rowIndicesBySymbol);
        applyExecutionDelay(table, rowIndicesBySymbol);
        sanitize(table);
        return table;
    }

    /** Feature columns per the Python {@code get_feature_columns} exclusion rules. */
    public static List<String> getFeatureColumns(List<String> columns) {
        List<String> features = new ArrayList<>();
        for (String column : columns) {
            if (EXCLUDED_COLUMNS.contains(column) || column.startsWith("target_")) {
                continue;
            }
            if (column.contains("market_cap") && !column.startsWith("log_")) {
                continue;
            }
            features.add(column);
        }
        return features;
    }

    // ------------------------------------------------------------------
    // Stock-level technical features
    // ------------------------------------------------------------------

    private List<Integer> computeSymbol(String symbol, List<Bar> bars, FeatureTable table) {
        int n = bars.size();
        double[] open = new double[n];
        double[] high = new double[n];
        double[] low = new double[n];
        double[] close = new double[n];
        double[] volume = new double[n];
        for (int i = 0; i < n; i++) {
            Bar bar = bars.get(i);
            open[i] = bar.open();
            high[i] = bar.high();
            low[i] = bar.low();
            close[i] = bar.close();
            volume[i] = bar.volume();
        }

        Map<String, double[]> stockFeatures = new LinkedHashMap<>();
        for (int window : RETURN_WINDOWS) {
            stockFeatures.put("log_return_" + window + "d", TechnicalIndicators.logReturns(close, window));
        }
        for (int window : MA_WINDOWS) {
            stockFeatures.put("ma_ratio_" + window + "d", TechnicalIndicators.movingAverageRatio(close, window));
        }
        stockFeatures.put("volatility_20d",
                TechnicalIndicators.rollingStd(stockFeatures.get("log_return_1d"), 20));
        stockFeatures.put("volume_change_1d", TechnicalIndicators.pctChange(volume));
        double[] volumeMa20 = TechnicalIndicators.rollingMean(volume, 20);
        stockFeatures.put("volume_ma_20d", volumeMa20);
        stockFeatures.put("volume_ratio_20d", TechnicalIndicators.volumeRatio(volume, 20));

        stockFeatures.put("rsi_14", TechnicalIndicators.wildersRsi(close, 14));

        TechnicalIndicators.Macd macd = TechnicalIndicators.macd(close);
        stockFeatures.put("macd_line", macd.line());
        stockFeatures.put("macd_signal", macd.signal());
        stockFeatures.put("macd_histogram", macd.histogram());

        TechnicalIndicators.Bollinger bb = TechnicalIndicators.bollinger(close, 20, 2.0);
        stockFeatures.put("bb_upper", bb.upper());
        stockFeatures.put("bb_middle", bb.middle());
        stockFeatures.put("bb_lower", bb.lower());
        stockFeatures.put("bb_width", bb.width());
        stockFeatures.put("bb_position", bb.position());

        double[] trueRange = TechnicalIndicators.trueRange(high, low, close);
        double[] atr = TechnicalIndicators.rollingMean(trueRange, 14);
        stockFeatures.put("true_range", trueRange);
        stockFeatures.put("atr_14", atr);
        stockFeatures.put("atr_14_ratio", divide(atr, close));

        for (int horizon : config.horizons()) {
            stockFeatures.put("target_return_" + horizon + "d", forwardLogReturn(close, horizon));
            stockFeatures.put("target_price_" + horizon + "d", forwardValue(close, horizon));
        }
        stockFeatures.put("target_o2c_1d", nextOpenToClose(open, close));

        List<Integer> indices = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            table.addRow(symbol, bars.get(i).timestamp());
            int row = table.size() - 1;
            indices.add(row);
            table.set(row, "open", open[i]);
            table.set(row, "high", high[i]);
            table.set(row, "low", low[i]);
            table.set(row, "close", close[i]);
            table.set(row, "volume", volume[i]);
            for (Map.Entry<String, double[]> feature : stockFeatures.entrySet()) {
                table.set(row, feature.getKey(), feature.getValue()[i]);
            }
        }
        return indices;
    }

    // ------------------------------------------------------------------
    // Market and sector features
    // ------------------------------------------------------------------

    private void mergeMarketFeatures(FeatureTable table, Map<String, List<Bar>> marketBySymbol) {
        Map<Instant, Map<String, Double>> marketWide = marketWideFeatures(marketBySymbol);
        if (marketWide.isEmpty()) {
            return;
        }
        Set<String> marketColumns = new LinkedHashSet<>();
        for (Map<String, Double> values : marketWide.values()) {
            marketColumns.addAll(values.keySet());
        }
        for (int row = 0; row < table.size(); row++) {
            Map<String, Double> values = marketWide.get(table.timestamp(row));
            if (values == null) {
                continue;
            }
            for (String column : marketColumns) {
                table.set(row, column, values.getOrDefault(column, Double.NaN));
            }
        }
    }

    private Map<Instant, Map<String, Double>> marketWideFeatures(Map<String, List<Bar>> marketBySymbol) {
        Map<Instant, Map<String, Double>> wide = new TreeMap<>();
        for (Map.Entry<String, List<Bar>> entry : marketBySymbol.entrySet()) {
            String symbol = entry.getKey();
            List<Bar> bars = entry.getValue();
            double[] close = new double[bars.size()];
            for (int i = 0; i < bars.size(); i++) {
                close[i] = bars.get(i).close();
            }
            double[] logReturn1d = TechnicalIndicators.logReturns(close, 1);
            double[] volatility20d = TechnicalIndicators.rollingStd(logReturn1d, 20);
            Map<Integer, double[]> returnsByWindow = new HashMap<>();
            for (int window : MARKET_RETURN_WINDOWS) {
                returnsByWindow.put(window, TechnicalIndicators.logReturns(close, window));
            }
            for (int i = 0; i < bars.size(); i++) {
                Map<String, Double> values = wide.computeIfAbsent(bars.get(i).timestamp(),
                        ts -> new LinkedHashMap<>());
                for (int window : MARKET_RETURN_WINDOWS) {
                    values.put("market_" + symbol + "_log_return_" + window + "d",
                            returnsByWindow.get(window)[i]);
                }
                values.put("vol_" + symbol + "_volatility_20d", volatility20d[i]);
            }
        }
        addSectorRelativeFeatures(wide);
        return wide;
    }

    private void addSectorRelativeFeatures(Map<Instant, Map<String, Double>> wide) {
        for (Map.Entry<Instant, Map<String, Double>> entry : wide.entrySet()) {
            Map<String, Double> values = entry.getValue();
            Double spy1d = values.get("market_SPY_log_return_1d");
            Double spy5d = values.get("market_SPY_log_return_5d");
            for (String etf : config.sectorEtfs()) {
                Double etf1d = values.get("market_" + etf + "_log_return_1d");
                Double etf5d = values.get("market_" + etf + "_log_return_5d");
                if (spy1d != null && etf1d != null) {
                    values.put("sector_rel_" + etf + "_1d", etf1d - spy1d);
                }
                if (spy5d != null && etf5d != null) {
                    values.put("sector_rel_" + etf + "_5d", etf5d - spy5d);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Residualized features and excess-return target
    // ------------------------------------------------------------------

    private void residualize(FeatureTable table) {
        if (!config.residualizeFeatures()) {
            return;
        }
        for (int row = 0; row < table.size(); row++) {
            for (int window : RESIDUAL_WINDOWS) {
                String stockColumn = "log_return_" + window + "d";
                String marketColumn = "market_SPY_log_return_" + window + "d";
                Double stock = table.value(row, stockColumn);
                Double market = table.value(row, marketColumn);
                if (stock != null && market != null) {
                    table.set(row, "resid_return_" + window + "d", stock - market);
                }
            }
        }
    }

    private void applyExcessReturnTarget(FeatureTable table, Map<String, List<Bar>> marketBySymbol) {
        if (!config.useExcessReturnTarget()) {
            return;
        }
        List<Bar> spy = marketBySymbol.get("SPY");
        if (spy == null || spy.isEmpty()) {
            return;
        }
        Map<Integer, Map<Instant, Double>> spyForward = new HashMap<>();
        for (int horizon : config.horizons()) {
            spyForward.put(horizon, forwardReturnsByTimestamp(spy, horizon));
        }
        Map<Instant, Double> spyForwardO2c = forwardOpenToCloseByTimestamp(spy);

        for (int row = 0; row < table.size(); row++) {
            Instant timestamp = table.timestamp(row);
            for (int horizon : config.horizons()) {
                Double forward = spyForward.get(horizon).get(timestamp);
                Double target = table.value(row, "target_return_" + horizon + "d");
                if (forward != null && target != null) {
                    table.set(row, "target_return_" + horizon + "d", target - forward);
                }
            }
            Double forwardO2c = spyForwardO2c.get(timestamp);
            Double targetO2c = table.value(row, "target_o2c_1d");
            if (forwardO2c != null && targetO2c != null) {
                table.set(row, "target_o2c_1d", targetO2c - forwardO2c);
            }
        }
    }

    private static Map<Instant, Double> forwardReturnsByTimestamp(List<Bar> bars, int horizon) {
        Map<Instant, Double> out = new HashMap<>();
        for (int i = 0; i + horizon < bars.size(); i++) {
            double current = bars.get(i).close();
            double future = bars.get(i + horizon).close();
            out.put(bars.get(i).timestamp(), Math.log(future / current));
        }
        return out;
    }

    private static Map<Instant, Double> forwardOpenToCloseByTimestamp(List<Bar> bars) {
        Map<Instant, Double> out = new HashMap<>();
        for (int i = 0; i + 1 < bars.size(); i++) {
            Bar next = bars.get(i + 1);
            out.put(bars.get(i).timestamp(), Math.log(next.close() / next.open()));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Fundamentals (sector dummies) and cross-sectional ranks
    // ------------------------------------------------------------------

    private void applySectorDummies(FeatureTable table, Map<String, List<Integer>> rowIndicesBySymbol) {
        if (config.sectorBySymbol().isEmpty()) {
            return;
        }
        Set<String> sectors = new LinkedHashSet<>(config.sectorBySymbol().values());
        for (Map.Entry<String, List<Integer>> entry : rowIndicesBySymbol.entrySet()) {
            String sector = config.sectorBySymbol().get(entry.getKey());
            for (int row : entry.getValue()) {
                for (String candidate : sectors) {
                    table.set(row, "sector_" + candidate, candidate.equals(sector) ? 1.0 : 0.0);
                }
                // pandas get_dummies(dummy_na=True) emits sector_nan as 0/1 on every row.
                table.set(row, "sector_nan", sector == null ? 1.0 : 0.0);
            }
        }
    }

    private void addCrossSectionalRanks(FeatureTable table, Map<String, List<Integer>> rowIndicesBySymbol) {
        List<String> baseColumns = List.of("log_return_20d", "volatility_20d", "rsi_14");
        Map<Instant, List<Integer>> rowsByTimestamp = new LinkedHashMap<>();
        for (int row = 0; row < table.size(); row++) {
            rowsByTimestamp.computeIfAbsent(table.timestamp(row), ts -> new ArrayList<>()).add(row);
        }
        for (Map.Entry<Instant, List<Integer>> entry : rowsByTimestamp.entrySet()) {
            List<Integer> rows = entry.getValue();
            for (String column : baseColumns) {
                double[] values = new double[rows.size()];
                for (int i = 0; i < rows.size(); i++) {
                    values[i] = table.valueOrNaN(rows.get(i), column);
                }
                double[] ranks = TechnicalIndicators.crossSectionalRank(values);
                for (int i = 0; i < rows.size(); i++) {
                    table.set(rows.get(i), column + "_rank", ranks[i]);
                }
            }
        }
        if (!config.sectorBySymbol().isEmpty()) {
            for (Map.Entry<Instant, List<Integer>> entry : rowsByTimestamp.entrySet()) {
                Map<String, List<Integer>> bySector = new LinkedHashMap<>();
                for (int row : entry.getValue()) {
                    String sector = config.sectorBySymbol().get(table.symbol(row));
                    bySector.computeIfAbsent(sector == null ? "nan" : sector, s -> new ArrayList<>()).add(row);
                }
                for (List<Integer> rows : bySector.values()) {
                    for (String column : baseColumns) {
                        double[] values = new double[rows.size()];
                        for (int i = 0; i < rows.size(); i++) {
                            values[i] = table.valueOrNaN(rows.get(i), column);
                        }
                        double[] ranks = TechnicalIndicators.crossSectionalRank(values);
                        for (int i = 0; i < rows.size(); i++) {
                            table.set(rows.get(i), column + "_sector_rank", ranks[i]);
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Execution delay and sanitization
    // ------------------------------------------------------------------

    private void applyExecutionDelay(FeatureTable table, Map<String, List<Integer>> rowIndicesBySymbol) {
        if (config.predictionDelay() <= 0) {
            return;
        }
        List<String> featureColumns = getFeatureColumns(table.columns());
        for (List<Integer> rows : rowIndicesBySymbol.values()) {
            for (String column : featureColumns) {
                double[] shifted = new double[rows.size()];
                for (int i = 0; i < rows.size(); i++) {
                    shifted[i] = i < config.predictionDelay()
                            ? Double.NaN
                            : table.valueOrNaN(rows.get(i - config.predictionDelay()), column);
                }
                for (int i = 0; i < rows.size(); i++) {
                    table.set(rows.get(i), column, shifted[i]);
                }
            }
        }
    }

    private static void sanitize(FeatureTable table) {
        // Hoisted: columns() is a defensive copy, so fetch it once, not per row.
        List<String> columns = table.columns();
        for (int row = 0; row < table.size(); row++) {
            for (String column : columns) {
                Double value = table.value(row, column);
                if (value != null && (value.isInfinite() || value.isNaN())) {
                    table.set(row, column, Double.NaN);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Map<String, List<Bar>> groupBySymbol(List<Bar> bars) {
        Map<String, List<Bar>> grouped = new TreeMap<>();
        for (Bar bar : bars) {
            grouped.computeIfAbsent(bar.symbol(), s -> new ArrayList<>()).add(bar);
        }
        for (List<Bar> symbolBars : grouped.values()) {
            symbolBars.sort(Comparator.comparing(Bar::timestamp));
        }
        return grouped;
    }

    private static double[] forwardLogReturn(double[] close, int horizon) {
        double[] out = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            out[i] = i + horizon < close.length ? Math.log(close[i + horizon] / close[i]) : Double.NaN;
        }
        return out;
    }

    private static double[] forwardValue(double[] close, int horizon) {
        double[] out = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            out[i] = i + horizon < close.length ? close[i + horizon] : Double.NaN;
        }
        return out;
    }

    private static double[] nextOpenToClose(double[] open, double[] close) {
        double[] out = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            out[i] = i + 1 < close.length ? Math.log(close[i + 1] / open[i + 1]) : Double.NaN;
        }
        return out;
    }

    private static double[] divide(double[] numerator, double[] denominator) {
        double[] out = new double[numerator.length];
        for (int i = 0; i < numerator.length; i++) {
            out[i] = denominator[i] == 0 ? Double.NaN : numerator[i] / denominator[i];
        }
        return out;
    }
}
