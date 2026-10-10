package com.stockpredict.prediction.service;

import java.time.LocalDate;
import java.util.List;

/** Outcome of one prediction batch run. */
public record BatchResult(LocalDate asOf, String modelVersion, int predictionCount,
                          List<Integer> horizons) {
}
