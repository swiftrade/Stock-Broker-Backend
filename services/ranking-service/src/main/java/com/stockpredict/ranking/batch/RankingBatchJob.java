package com.stockpredict.ranking.batch;

import com.stockpredict.ranking.service.RankingResult;
import com.stockpredict.ranking.service.RankingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly ranking trigger (02:00 ET, Tuesday–Saturday, i.e. after each trading
 * day). A missed run leaves the previous ranking in place for the API.
 *
 * <p>Disabled in production via {@code stockpredict.ranking.scheduling-enabled=false}
 * because the Kubernetes CronJob is the single execution model there (D2).
 */
@Component
@ConditionalOnProperty(prefix = "stockpredict.ranking", name = "scheduling-enabled",
        havingValue = "true", matchIfMissing = true)
public class RankingBatchJob {

    private static final Logger log = LoggerFactory.getLogger(RankingBatchJob.class);

    private final RankingService rankingService;

    public RankingBatchJob(RankingService rankingService) {
        this.rankingService = rankingService;
    }

    @Scheduled(cron = "${stockpredict.ranking.batch-cron:0 0 2 * * TUE-SAT}",
            zone = "America/New_York")
    public void nightly() {
        try {
            RankingResult result = rankingService.run();
            log.info("Nightly ranking complete: {}", result);
        } catch (Exception ex) {
            log.error("Nightly ranking failed; serving the previous run", ex);
        }
    }
}
