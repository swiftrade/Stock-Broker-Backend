package com.stockpredict.feature.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Train/serve-skew guard (plan gate: max abs difference ≤ 1e-9 per feature,
 * identical NaN pattern). Golden files are produced by A9:
 *
 * <pre>
 *   ml-export/golden/features_golden.csv   stock raw bars + Python features
 *   ml-export/golden/market_golden.csv     market/sector ETF raw bars
 *   ml-export/golden/sector_golden.csv     symbol,sector (optional)
 *   ml-export/golden/meta.properties       engine settings + warm-up
 * </pre>
 *
 * The test recomputes the whole window with {@link FeatureEngine} and compares
 * every golden feature column row-by-row after the warm-up period. It skips
 * itself until A9 has produced the files.
 */
class GoldenParityTest {

    private static final Path GOLDEN_DIR = Path.of("src/test/resources/golden");
    private static final double TOLERANCE = 1e-9;

    private static final List<String> RAW_COLUMNS =
            List.of("symbol", "timestamp", "open", "high", "low", "close", "volume");

    @Test
    void javaFeaturesMatchPythonGoldenFile() throws IOException {
        Path featuresPath = GOLDEN_DIR.resolve("features_golden.csv");
        Path marketPath = GOLDEN_DIR.resolve("market_golden.csv");
        Path metaPath = GOLDEN_DIR.resolve("meta.properties");
        Assumptions.assumeTrue(Files.exists(featuresPath) && Files.exists(marketPath) && Files.exists(metaPath),
                "No golden files in " + GOLDEN_DIR.toAbsolutePath()
                        + " (run ml-export/export_models.py --generate-golden)");

        Properties meta = loadProperties(metaPath);
        EngineConfig config = engineConfig(meta);
        int warmup = Integer.parseInt(meta.getProperty("warmup", "60"));

        Csv stockCsv = readCsv(featuresPath);
        Csv marketCsv = readCsv(marketPath);

        FeatureTable table = new FeatureEngine(config).compute(
                toBars(stockCsv), toBars(marketCsv));

        Map<String, Integer> javaRows = new HashMap<>();
        for (int row = 0; row < table.size(); row++) {
            javaRows.put(key(table.symbol(row), table.timestamp(row)), row);
        }

        int compared = 0;
        int skipped = 0;
        Map<String, Integer> perSymbolCount = new HashMap<>();
        for (int goldenRow = 0; goldenRow < stockCsv.rowCount(); goldenRow++) {
            String symbol = stockCsv.value(goldenRow, "symbol");
            int seen = perSymbolCount.merge(symbol, 1, Integer::sum);
            if (seen <= warmup) {
                skipped++;
                continue;
            }
            Instant timestamp = Instant.parse(stockCsv.value(goldenRow, "timestamp"));
            Integer javaRow = javaRows.get(key(symbol, timestamp));
            assertThat(javaRow).as("Java row for %s at %s", symbol, timestamp).isNotNull();

            for (String column : stockCsv.columns()) {
                if (RAW_COLUMNS.contains(column)) {
                    continue;
                }
                double expected = parseDouble(stockCsv.value(goldenRow, column));
                double actual = table.valueOrNaN(javaRow, column);
                boolean bothNaN = Double.isNaN(expected) && Double.isNaN(actual);
                boolean oneNaN = Double.isNaN(expected) ^ Double.isNaN(actual);
                assertThat(oneNaN)
                        .as("NaN pattern mismatch for %s at %s: expected %s actual %s",
                                column, timestamp, expected, actual)
                        .isFalse();
                if (!bothNaN) {
                    assertThat(Math.abs(actual - expected))
                            .as("Parity mismatch for %s %s at %s: java=%s python=%s",
                                    symbol, column, timestamp, actual, expected)
                            .isLessThanOrEqualTo(TOLERANCE);
                }
                compared++;
            }
        }
        assertThat(compared).as("No feature columns were compared").isGreaterThan(0);
    }

    private static EngineConfig engineConfig(Properties meta) {
        EngineConfig.Builder builder = EngineConfig.builder()
                .predictionDelay(Integer.parseInt(meta.getProperty("predictionDelay", "1")))
                .horizons(intList(meta.getProperty("horizons", "1,5,20")))
                .marketSymbols(stringList(meta.getProperty("marketSymbols", "SPY,QQQ,IWM")))
                .sectorEtfs(stringList(meta.getProperty("sectorEtfs", "")))
                .residualizeFeatures(Boolean.parseBoolean(meta.getProperty("residualizeFeatures", "true")))
                .useExcessReturnTarget(Boolean.parseBoolean(meta.getProperty("useExcessReturnTarget", "true")));
        Path sectors = GOLDEN_DIR.resolve("sector_golden.csv");
        if (Files.exists(sectors)) {
            try {
                Csv sectorCsv = readCsv(sectors);
                Map<String, String> sectorBySymbol = new LinkedHashMap<>();
                for (int row = 0; row < sectorCsv.rowCount(); row++) {
                    sectorBySymbol.put(sectorCsv.value(row, "symbol"), sectorCsv.value(row, "sector"));
                }
                builder.sectorBySymbol(sectorBySymbol);
            } catch (IOException ex) {
                throw new IllegalStateException("Unable to read sector golden file", ex);
            }
        }
        return builder.build();
    }

    private static List<Bar> toBars(Csv csv) {
        List<Bar> bars = new ArrayList<>(csv.rowCount());
        for (int row = 0; row < csv.rowCount(); row++) {
            bars.add(new Bar(
                    csv.value(row, "symbol"),
                    Instant.parse(csv.value(row, "timestamp")),
                    parseDouble(csv.value(row, "open")),
                    parseDouble(csv.value(row, "high")),
                    parseDouble(csv.value(row, "low")),
                    parseDouble(csv.value(row, "close")),
                    parseDouble(csv.value(row, "volume"))));
        }
        return bars;
    }

    private static String key(String symbol, Instant timestamp) {
        return symbol + "@" + timestamp;
    }

    private static double parseDouble(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("nan")) {
            return Double.NaN;
        }
        return Double.parseDouble(value);
    }

    private static Properties loadProperties(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
        }
        return properties;
    }

    private static List<Integer> intList(String csv) {
        List<Integer> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            if (!part.isBlank()) {
                out.add(Integer.parseInt(part.trim()));
            }
        }
        return out;
    }

    private static List<String> stringList(String csv) {
        List<String> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    /** Minimal CSV reader (no quoted fields are produced by ml-export). */
    private static Csv readCsv(Path path) throws IOException {
        List<String> headers = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return new Csv(headers, rows);
            }
            for (String header : headerLine.split(",")) {
                headers.add(header.trim());
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    rows.add(line.split(",", -1));
                }
            }
        }
        return new Csv(headers, rows);
    }

    private record Csv(List<String> headers, List<String[]> rows) {

        int rowCount() {
            return rows.size();
        }

        List<String> columns() {
            return headers;
        }

        String value(int row, String column) {
            int index = headers.indexOf(column);
            if (index < 0) {
                return null;
            }
            String[] values = rows.get(row);
            return index < values.length ? values[index].trim() : null;
        }
    }
}
