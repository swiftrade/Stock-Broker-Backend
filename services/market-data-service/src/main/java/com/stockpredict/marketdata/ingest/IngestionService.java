package com.stockpredict.marketdata.ingest;

import com.stockpredict.common.time.MarketClock;
import com.stockpredict.marketdata.api.model.Bar;
import com.stockpredict.marketdata.guard.GuardResult;
import com.stockpredict.marketdata.guard.IngestionQualityGuard;
import com.stockpredict.marketdata.provider.MarketDataProvider;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fetches bars from the provider, runs the quality guard, upserts the accepted
 * bars and publishes {@code bars.updated}. A failing symbol never aborts the
 * rest of the batch. Daily ingestion is incremental: a symbol that already has
 * bars is only fetched from the day after its latest stored bar.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final MarketDataProvider provider;
    private final BarRepository repository;
    private final IngestionQualityGuard guard;
    private final BarsUpdatedPublisher publisher;
    private final MarketClock clock;
    private final MeterRegistry meterRegistry;

    public IngestionService(MarketDataProvider provider,
                            BarRepository repository,
                            IngestionQualityGuard guard,
                            BarsUpdatedPublisher publisher,
                            MarketClock clock,
                            MeterRegistry meterRegistry) {
        this.provider = provider;
        this.repository = repository;
        this.guard = guard;
        this.publisher = publisher;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    public IngestionResult ingestDaily(List<String> symbols, int backfillYears) {
        LocalDate to = clock.todayEt();
        LocalDate fullFrom = to.minusYears(Math.max(1, backfillYears));
        return ingest(symbols, "1d", to, false, symbol -> incrementalFrom(symbol, fullFrom));
    }

    public IngestionResult ingestIntraday(List<String> symbols, int days) {
        LocalDate to = clock.todayEt();
        LocalDate from = to.minusDays(Math.max(1, days));
        boolean live = days <= 1;
        return ingest(symbols, "5m", to, live, symbol -> from);
    }

    /** The explicit ingestion universe, or every stored symbol on a fresh database. */
    public List<String> universeSymbols() {
        List<String> universe = repository.listUniverseSymbols();
        return universe.isEmpty() ? repository.listSymbols() : universe;
    }

    /** Records symbols as members of the explicit ingestion universe. */
    public int registerUniverseSymbols(List<String> symbols) {
        return repository.registerUniverseSymbols(symbols, "universe");
    }

    private LocalDate incrementalFrom(String symbol, LocalDate fullFrom) {
        LocalDate next = repository.latestDailyTimestamp(symbol)
                .map(latest -> latest.plusDays(1))
                .orElse(fullFrom);
        return next.isAfter(fullFrom) ? next : fullFrom;
    }

    private IngestionResult ingest(List<String> symbols, String interval, LocalDate to,
                                   boolean enforceStaleness, Function<String, LocalDate> fromResolver) {
        if (symbols == null || symbols.isEmpty()) {
            return IngestionResult.empty();
        }
        int succeeded = 0;
        int failed = 0;
        int accepted = 0;
        int rejected = 0;
        int flagged = 0;

        for (String symbol : symbols) {
            try {
                LocalDate from = fromResolver.apply(symbol);
                if (from.isAfter(to)) {
                    log.debug("Skipping {} {}: already current through {}", symbol, interval, to);
                    succeeded++;
                    continue;
                }
                List<Bar> fetched = "1d".equals(interval)
                        ? provider.fetchDailyBars(symbol, from, to)
                        : provider.fetchIntradayBars(symbol, from, to);
                GuardResult result = guard.validate(fetched, interval, enforceStaleness);
                if (!result.accepted().isEmpty()) {
                    if ("1d".equals(interval)) {
                        repository.upsertDaily(symbol, provider.source(), result.accepted());
                    } else {
                        repository.upsertIntraday(symbol, provider.source(), result.accepted());
                    }
                    publisher.publish(symbol, interval, result.accepted(), provider.source());
                }
                accepted += result.acceptedCount();
                rejected += result.rejectedCount();
                flagged += result.flaggedCount();
                succeeded++;
            } catch (Exception ex) {
                failed++;
                log.warn("Ingestion failed for {} {}: {}", symbol, interval, ex.toString());
            }
        }
        IngestionResult result = new IngestionResult(symbols.size(), succeeded, failed, accepted, rejected, flagged);
        meterRegistry.counter("stockpredict.ingestion.bars", "interval", interval, "outcome", "accepted")
                .increment(accepted);
        meterRegistry.counter("stockpredict.ingestion.bars", "interval", interval, "outcome", "rejected")
                .increment(rejected);
        meterRegistry.counter("stockpredict.ingestion.bars", "interval", interval, "outcome", "flagged")
                .increment(flagged);
        meterRegistry.counter("stockpredict.ingestion.symbols", "interval", interval, "outcome", "failed")
                .increment(failed);
        log.info("Ingested {} {} bars: {}", interval, symbols.size(), result);
        return result;
    }
}
