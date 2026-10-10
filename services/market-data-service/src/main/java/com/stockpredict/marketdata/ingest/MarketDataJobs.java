package com.stockpredict.marketdata.ingest;

import com.stockpredict.common.time.MarketClock;
import com.stockpredict.common.time.TradingCalendar;
import com.stockpredict.marketdata.config.MarketDataProperties;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduled ingestion: nightly daily-bar refresh and 5-minute live refresh. */
@Component
public class MarketDataJobs {

    private static final Logger log = LoggerFactory.getLogger(MarketDataJobs.class);

    private final IngestionService ingestionService;
    private final UniverseProvider universeProvider;
    private final MarketDataProperties properties;
    private final TradingCalendar tradingCalendar;
    private final MarketClock clock;

    public MarketDataJobs(IngestionService ingestionService,
                          UniverseProvider universeProvider,
                          MarketDataProperties properties,
                          TradingCalendar tradingCalendar,
                          MarketClock clock) {
        this.ingestionService = ingestionService;
        this.universeProvider = universeProvider;
        this.properties = properties;
        this.tradingCalendar = tradingCalendar;
        this.clock = clock;
    }

    @Scheduled(cron = "${stockpredict.market-data.daily-cron:0 15 6 * * TUE-SAT}",
            zone = "America/New_York")
    public void dailyRefresh() {
        // In production the Kubernetes CronJob owns the nightly backfill (D2);
        // the intraday refresh below still runs in-pod.
        if (!properties.isDailySchedulingEnabled()) {
            log.debug("In-pod daily refresh disabled; relying on the ingestion CronJob");
            return;
        }
        try {
            List<String> symbols = universeProvider.symbols();
            ingestionService.registerUniverseSymbols(symbols);
            IngestionResult result = ingestionService.ingestDaily(symbols, properties.getDailyBackfillYears());
            log.info("Daily market-data refresh complete: {}", result);
        } catch (Exception ex) {
            log.error("Daily market-data refresh failed", ex);
        }
    }

    @Scheduled(cron = "0 */5 9-16 * * MON-FRI", zone = "America/New_York")
    public void intradayRefresh() {
        if (!tradingCalendar.isMarketOpen(clock.now())) {
            return;
        }
        try {
            List<String> symbols = trackedSymbols();
            IngestionResult result = ingestionService.ingestIntraday(symbols, 1);
            log.info("Intraday market-data refresh complete: {}", result);
        } catch (Exception ex) {
            log.error("Intraday market-data refresh failed", ex);
        }
    }

    private List<String> trackedSymbols() {
        LinkedHashSet<String> symbols = new LinkedHashSet<>(ingestionService.universeSymbols());
        symbols.addAll(properties.getMarketSymbols());
        symbols.addAll(properties.getSectorEtfs());
        return List.copyOf(symbols);
    }
}
