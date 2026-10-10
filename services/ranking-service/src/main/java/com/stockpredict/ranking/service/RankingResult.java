package com.stockpredict.ranking.service;

import java.time.LocalDate;

/** Outcome of one nightly ranking run. */
public record RankingResult(LocalDate runDate, String modelVersion, int pickCount) {
}
