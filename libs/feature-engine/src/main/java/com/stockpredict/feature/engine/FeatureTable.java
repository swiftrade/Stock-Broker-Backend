package com.stockpredict.feature.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Columnar feature table: one {@code double[]} per column plus parallel
 * symbol/timestamp lists. Column order is preserved for parity with the Python
 * DataFrame, and the model metadata decides the final serving order. Cells that
 * were never set read as {@code NaN} (the missing-value marker throughout the
 * engine); {@link #value} returns {@code null} only for an unknown column.
 */
public final class FeatureTable {

    private final List<String> symbols = new ArrayList<>();
    private final List<Instant> timestamps = new ArrayList<>();
    private final Map<String, double[]> columns = new LinkedHashMap<>();

    public void addRow(String symbol, Instant timestamp) {
        symbols.add(symbol);
        timestamps.add(timestamp);
    }

    public void set(int rowIndex, String column, double value) {
        double[] values = columns.get(column);
        if (values == null) {
            values = new double[symbols.size()];
            columns.put(column, values);
        } else if (rowIndex >= values.length) {
            values = Arrays.copyOf(values, symbols.size());
            columns.put(column, values);
        }
        values[rowIndex] = value;
    }

    public int size() {
        return symbols.size();
    }

    public String symbol(int rowIndex) {
        return symbols.get(rowIndex);
    }

    public Instant timestamp(int rowIndex) {
        return timestamps.get(rowIndex);
    }

    public Double value(int rowIndex, String column) {
        double[] values = columns.get(column);
        if (values == null || rowIndex >= values.length) {
            return null;
        }
        return values[rowIndex];
    }

    public double valueOrNaN(int rowIndex, String column) {
        Double value = value(rowIndex, column);
        return value == null ? Double.NaN : value;
    }

    public List<String> columns() {
        return List.copyOf(columns.keySet());
    }

    public List<Row> rows() {
        List<Row> out = new ArrayList<>(symbols.size());
        for (int row = 0; row < symbols.size(); row++) {
            Map<String, Double> values = new LinkedHashMap<>();
            for (Map.Entry<String, double[]> column : columns.entrySet()) {
                values.put(column.getKey(), column.getValue()[row]);
            }
            out.add(new Row(symbols.get(row), timestamps.get(row), Map.copyOf(values)));
        }
        return out;
    }

    /** Immutable view of one row. */
    public record Row(String symbol, Instant timestamp, Map<String, Double> values) {
    }
}
