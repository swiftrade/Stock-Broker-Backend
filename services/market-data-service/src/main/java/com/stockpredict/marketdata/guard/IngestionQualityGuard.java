package com.stockpredict.marketdata.guard;

import com.stockpredict.common.time.MarketClock;
import com.stockpredict.marketdata.api.model.Bar;
import com.stockpredict.marketdata.config.MarketDataProperties;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Batch port of the essential checks from the Python {@code marketdata_guard}:
 * future/stale timestamps, price sanity, duplicate/out-of-order timestamps and
 * close-to-close deviation. Historical backfills pass
 * {@code enforceIntradayStaleness = false} so old bars are not rejected.
 */
@Component
public class IngestionQualityGuard {

    private static final Logger log = LoggerFactory.getLogger(IngestionQualityGuard.class);

    private final MarketDataProperties properties;
    private final MarketClock clock;

    public IngestionQualityGuard(MarketDataProperties properties, MarketClock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Live semantics: intraday staleness enforced for 5m batches. */
    public GuardResult validate(List<Bar> bars, String interval) {
        return validate(bars, interval, "5m".equals(interval));
    }

    public GuardResult validate(List<Bar> bars, String interval, boolean enforceIntradayStaleness) {
        if (bars == null || bars.isEmpty()) {
            return new GuardResult(List.of(), List.of(), List.of());
        }
        MarketDataProperties.Guard config = properties.getGuard();
        Instant now = clock.now();
        Instant futureLimit = now.plus(config.getFutureTolerance());
        Instant staleLimit = now.minus(config.getIntradayStaleness());
        boolean intraday = "5m".equals(interval);

        List<Bar> accepted = new ArrayList<>();
        List<Bar> rejected = new ArrayList<>();
        List<Bar> flagged = new ArrayList<>();
        Instant lastTimestamp = null;
        Double previousClose = null;

        for (Bar bar : bars) {
            String reason = rejectionReason(bar, futureLimit, staleLimit, intraday, enforceIntradayStaleness, lastTimestamp);
            if (reason != null) {
                rejected.add(bar);
                log.warn("Rejected {} bar at {}: {}", interval, bar.getTs(), reason);
                continue;
            }
            boolean deviated = false;
            if (previousClose != null && previousClose > 0 && bar.getClose() != null) {
                double deviation = Math.abs(bar.getClose() - previousClose) / previousClose;
                if (deviation > config.getMaxDeviationPct()) {
                    deviated = true;
                    flagged.add(bar);
                    log.warn("Flagged {} bar at {}: close {} deviates {}% from previous close {}",
                            interval, bar.getTs(), bar.getClose(), deviation * 100, previousClose);
                }
            }
            accepted.add(bar);
            lastTimestamp = bar.getTs().toInstant();
            if (!deviated) {
                previousClose = bar.getClose();
            }
        }
        return new GuardResult(accepted, rejected, flagged);
    }

    private static String rejectionReason(Bar bar, Instant futureLimit, Instant staleLimit,
                                          boolean intraday, boolean enforceStaleness, Instant lastTimestamp) {
        OffsetDateTime ts = bar.getTs();
        if (ts == null) {
            return "missing timestamp";
        }
        Instant instant = ts.toInstant();
        if (instant.isAfter(futureLimit)) {
            return "timestamp in the future: " + ts;
        }
        if (intraday && enforceStaleness && instant.isBefore(staleLimit)) {
            return "stale intraday timestamp: " + ts;
        }
        if (lastTimestamp != null && !instant.isAfter(lastTimestamp)) {
            return "duplicate or out-of-order timestamp: " + ts;
        }
        return priceIssue(bar);
    }

    private static String priceIssue(Bar bar) {
        Double open = bar.getOpen();
        Double high = bar.getHigh();
        Double low = bar.getLow();
        Double close = bar.getClose();
        if (open == null || high == null || low == null || close == null) {
            return "missing price";
        }
        if (open <= 0 || high <= 0 || low <= 0 || close <= 0) {
            return "non-positive price";
        }
        if (high < low) {
            return "high < low";
        }
        if (close < low || close > high) {
            return "close outside [low, high]";
        }
        return null;
    }
}
