package com.stockpredict.marketdata.ingest;

/** Aggregate outcome of an ingestion batch. */
public record IngestionResult(
        int symbolsRequested,
        int symbolsSucceeded,
        int symbolsFailed,
        int barsAccepted,
        int barsRejected,
        int barsFlagged) {

    public static IngestionResult empty() {
        return new IngestionResult(0, 0, 0, 0, 0, 0);
    }

    public int barsIngested() {
        return barsAccepted;
    }
}
