package com.stockpredict.ranking.data;

import java.time.LocalDate;

/** One row of the {@code rankings} table. */
public record RankingRow(
        LocalDate runDate,
        String modelVersion,
        String symbol,
        int rank,
        Integer rankChange,
        double predicted20dReturn,
        Double volatility,
        String sector,
        double weight,
        String rationaleJson) {
}
