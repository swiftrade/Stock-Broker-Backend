package com.stockpredict.prediction.batch;

import com.stockpredict.common.time.MarketClock;
import com.stockpredict.common.time.TradingCalendar;
import com.stockpredict.prediction.service.BatchResult;
import com.stockpredict.prediction.service.PredictionBatchService;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pre-market batch trigger (08:30 ET on trading days by default). A missed run
 * is not fatal: the API serves the previous run flagged {@code stale: true}.
 *
 * <p>Disabled in production via {@code stockpredict.predictions.scheduling-enabled=false}
 * because the Kubernetes CronJob is the single execution model there (D2).
 */
@Component
@ConditionalOnProperty(prefix = "stockpredict.predictions", name = "scheduling-enabled",
        havingValue = "true", matchIfMissing = true)
public class PredictionBatchJob {

    private static final Logger log = LoggerFactory.getLogger(PredictionBatchJob.class);

    private final PredictionBatchService batchService;
    private final TradingCalendar tradingCalendar;
    private final MarketClock clock;

    public PredictionBatchJob(PredictionBatchService batchService,
                              TradingCalendar tradingCalendar,
                              MarketClock clock) {
        this.batchService = batchService;
        this.tradingCalendar = tradingCalendar;
        this.clock = clock;
    }

    @Scheduled(cron = "${stockpredict.predictions.batch-cron:0 30 8 * * MON-FRI}",
            zone = "America/New_York")
    public void preMarket() {
        LocalDate today = clock.todayEt();
        if (!tradingCalendar.isTradingDay(today)) {
            log.info("Skipping prediction batch: {} is not a trading day", today);
            return;
        }
        try {
            BatchResult result = batchService.run();
            log.info("Pre-market prediction batch complete: {}", result);
        } catch (Exception ex) {
            log.error("Pre-market prediction batch failed; serving previous predictions as stale", ex);
        }
    }
}
