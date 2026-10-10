package com.stockpredict.feature.engine.indicators;

/**
 * Vectorized technical indicators matching the pandas semantics used in the
 * Python pipeline ({@code STOCKMARKETANALYSIS/features.py}). All methods return
 * a new array of the same length; positions without enough history are
 * {@code Double.NaN}. Sample standard deviation (ddof=1) and
 * {@code ewm(adjust=false)} semantics are preserved.
 */
public final class TechnicalIndicators {

    private TechnicalIndicators() {
    }

    /** log(close[t] / close[t-lag]); NaN for the first {@code lag} entries. */
    public static double[] logReturns(double[] close, int lag) {
        requirePositive(lag, "lag");
        double[] out = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            out[i] = i < lag ? Double.NaN : Math.log(close[i] / close[i - lag]);
        }
        return out;
    }

    /** Simple percentage change; NaN for the first entry. */
    public static double[] pctChange(double[] values) {
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = i == 0 ? Double.NaN : values[i] / values[i - 1] - 1.0;
        }
        return out;
    }

    /** Rolling mean; NaN until {@code window} observations exist. */
    public static double[] rollingMean(double[] values, int window) {
        requirePositive(window, "window");
        double[] out = new double[values.length];
        double sum = 0;
        for (int i = 0; i < values.length; i++) {
            sum += values[i];
            if (i >= window) {
                sum -= values[i - window];
            }
            out[i] = i >= window - 1 ? sum / window : Double.NaN;
        }
        return out;
    }

    /** Rolling sample standard deviation (ddof=1), matching pandas {@code .std()}. */
    public static double[] rollingStd(double[] values, int window) {
        requirePositive(window, "window");
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            if (i < window - 1) {
                out[i] = Double.NaN;
                continue;
            }
            double mean = 0;
            for (int j = i - window + 1; j <= i; j++) {
                mean += values[j];
            }
            mean /= window;
            double sumSq = 0;
            for (int j = i - window + 1; j <= i; j++) {
                double diff = values[j] - mean;
                sumSq += diff * diff;
            }
            out[i] = Math.sqrt(sumSq / (window - 1));
        }
        return out;
    }

    /** close / rollingMean(close, window). */
    public static double[] movingAverageRatio(double[] close, int window) {
        double[] mean = rollingMean(close, window);
        double[] out = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            out[i] = Double.isNaN(mean[i]) ? Double.NaN : close[i] / mean[i];
        }
        return out;
    }

    /**
     * EMA with {@code adjust=false} (pandas ewm): alpha = 2 / (span + 1),
     * seeded with the first observation.
     */
    public static double[] ema(double[] values, int span) {
        requirePositive(span, "span");
        double alpha = 2.0 / (span + 1);
        double[] out = new double[values.length];
        if (values.length == 0) {
            return out;
        }
        out[0] = values[0];
        for (int i = 1; i < values.length; i++) {
            out[i] = out[i - 1] + alpha * (values[i] - out[i - 1]);
        }
        return out;
    }

    /**
     * Wilder RSI, matching {@code ewm(alpha=1/period, adjust=false)} on gains and
     * losses. Python's {@code delta.where(delta > 0, 0.0)} makes the first delta
     * 0.0 (NaN comparisons are false), so the EWM is seeded at 0, not at the
     * first real gain. Returns NaN while avgLoss == 0 (Python replaces zero with
     * NaN), which is also the all-gains case.
     */
    public static double[] wildersRsi(double[] close, int period) {
        requirePositive(period, "period");
        double[] out = new double[close.length];
        double alpha = 1.0 / period;
        double avgGain = 0.0;
        double avgLoss = 0.0;
        for (int i = 0; i < close.length; i++) {
            if (i == 0) {
                out[i] = Double.NaN;
                continue;
            }
            double delta = close[i] - close[i - 1];
            double gain = Math.max(delta, 0.0);
            double loss = Math.max(-delta, 0.0);
            avgGain += alpha * (gain - avgGain);
            avgLoss += alpha * (loss - avgLoss);
            if (avgLoss == 0.0) {
                out[i] = Double.NaN;
            } else {
                double rs = avgGain / avgLoss;
                out[i] = 100.0 - (100.0 / (1.0 + rs));
            }
        }
        return out;
    }

    /** MACD line, signal line and histogram (12/26/9 defaults). */
    public static Macd macd(double[] close) {
        return macd(close, 12, 26, 9);
    }

    public static Macd macd(double[] close, int fast, int slow, int signal) {
        double[] fastEma = ema(close, fast);
        double[] slowEma = ema(close, slow);
        double[] line = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            line[i] = fastEma[i] - slowEma[i];
        }
        double[] signalLine = ema(line, signal);
        double[] histogram = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            histogram[i] = line[i] - signalLine[i];
        }
        return new Macd(line, signalLine, histogram);
    }

    /** Bollinger bands (20-day, 2 std by default) plus width and position. */
    public static Bollinger bollinger(double[] close, int window, double numStd) {
        double[] middle = rollingMean(close, window);
        double[] std = rollingStd(close, window);
        double[] upper = new double[close.length];
        double[] lower = new double[close.length];
        double[] width = new double[close.length];
        double[] position = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            if (Double.isNaN(middle[i]) || Double.isNaN(std[i])) {
                upper[i] = lower[i] = width[i] = position[i] = Double.NaN;
                continue;
            }
            upper[i] = middle[i] + numStd * std[i];
            lower[i] = middle[i] - numStd * std[i];
            double band = upper[i] - lower[i];
            width[i] = middle[i] == 0 ? Double.NaN : band / middle[i];
            position[i] = band == 0 ? Double.NaN : (close[i] - lower[i]) / band;
        }
        return new Bollinger(upper, middle, lower, width, position);
    }

    /** Average True Range (rolling mean of true range), matching features.py. */
    public static double[] atr(double[] high, double[] low, double[] close, int window) {
        double[] trueRange = trueRange(high, low, close);
        return rollingMean(trueRange, window);
    }

    public static double[] trueRange(double[] high, double[] low, double[] close) {
        double[] out = new double[close.length];
        for (int i = 0; i < close.length; i++) {
            if (i == 0) {
                out[i] = high[i] - low[i];
                continue;
            }
            double prevClose = close[i - 1];
            out[i] = Math.max(high[i] - low[i],
                    Math.max(Math.abs(high[i] - prevClose), Math.abs(low[i] - prevClose)));
        }
        return out;
    }

    /** volume / rollingMean(volume, window). */
    public static double[] volumeRatio(double[] volume, int window) {
        double[] mean = rollingMean(volume, window);
        double[] out = new double[volume.length];
        for (int i = 0; i < volume.length; i++) {
            out[i] = Double.isNaN(mean[i]) || mean[i] == 0 ? Double.NaN : volume[i] / mean[i];
        }
        return out;
    }

    /**
     * Cross-sectional percentile rank, matching pandas {@code rank(pct=true)}
     * with average ties. NaNs are ignored and remain NaN.
     */
    public static double[] crossSectionalRank(double[] values) {
        double[] out = new double[values.length];
        int validCount = 0;
        for (double value : values) {
            if (!Double.isNaN(value)) {
                validCount++;
            }
        }
        for (int i = 0; i < values.length; i++) {
            if (Double.isNaN(values[i])) {
                out[i] = Double.NaN;
                continue;
            }
            double less = 0;
            double equal = 0;
            for (double other : values) {
                if (Double.isNaN(other)) {
                    continue;
                }
                if (other < values[i]) {
                    less++;
                } else if (other == values[i]) {
                    equal++;
                }
            }
            out[i] = (less + (equal + 1) / 2.0) / validCount;
        }
        return out;
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /** MACD output bundle. */
    public record Macd(double[] line, double[] signal, double[] histogram) {
    }

    /** Bollinger output bundle. */
    public record Bollinger(double[] upper, double[] middle, double[] lower,
                            double[] width, double[] position) {
    }
}
