package com.stockpredict.prediction.data;

import java.time.LocalDate;

/** One row of the {@code predictions} table (features stored as JSON text). */
public record PredictionRow(
        String symbol,
        LocalDate asOf,
        int horizonDays,
        double predictedLogReturn,
        Double pUp,
        Double confidence,
        Double currentClose,
        Double predictedPrice,
        String featuresJson,
        String modelVersion) {
}
