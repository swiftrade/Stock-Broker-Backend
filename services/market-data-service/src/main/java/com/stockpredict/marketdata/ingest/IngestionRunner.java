package com.stockpredict.marketdata.ingest;

import com.stockpredict.marketdata.config.MarketDataProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * One-shot batch mode for Kubernetes CronJobs:
 * {@code stockpredict.market-data.run-ingest-once=true} runs the daily and
 * intraday backfills and exits 0/1 instead of starting a long-lived API server.
 */
@Component
@ConditionalOnProperty(prefix = "stockpredict.market-data", name = "run-ingest-once", havingValue = "true")
public class IngestionRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionRunner.class);

    private final IngestionService ingestionService;
    private final UniverseProvider universeProvider;
    private final MarketDataProperties properties;
    private final ApplicationContext context;

    public IngestionRunner(IngestionService ingestionService,
                           UniverseProvider universeProvider,
                           MarketDataProperties properties,
                           ApplicationContext context) {
        this.ingestionService = ingestionService;
        this.universeProvider = universeProvider;
        this.properties = properties;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = 0;
        try {
            List<String> symbols = universeProvider.symbols();
            ingestionService.registerUniverseSymbols(symbols);
            IngestionResult daily = ingestionService.ingestDaily(symbols, properties.getDailyBackfillYears());
            IngestionResult intraday = ingestionService.ingestIntraday(symbols, properties.getIntradayDays());
            log.info("One-shot market-data ingestion complete: daily={} intraday={}", daily, intraday);
        } catch (Exception ex) {
            log.error("One-shot market-data ingestion failed", ex);
            exitCode = 1;
        }
        int code = exitCode;
        System.exit(SpringApplication.exit(context, () -> code));
    }
}
