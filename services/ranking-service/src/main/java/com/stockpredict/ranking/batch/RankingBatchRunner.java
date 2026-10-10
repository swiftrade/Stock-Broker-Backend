package com.stockpredict.ranking.batch;

import com.stockpredict.ranking.service.RankingResult;
import com.stockpredict.ranking.service.RankingService;
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
 * {@code stockpredict.ranking.run-batch-once=true} runs the nightly ranking and
 * exits 0/1.
 */
@Component
@ConditionalOnProperty(prefix = "stockpredict.ranking", name = "run-batch-once", havingValue = "true")
public class RankingBatchRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RankingBatchRunner.class);

    private final RankingService rankingService;
    private final ApplicationContext context;

    public RankingBatchRunner(RankingService rankingService, ApplicationContext context) {
        this.rankingService = rankingService;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = 0;
        try {
            RankingResult result = rankingService.run();
            log.info("One-shot ranking complete: {}", result);
        } catch (Exception ex) {
            log.error("One-shot ranking failed", ex);
            exitCode = 1;
        }
        int code = exitCode;
        System.exit(SpringApplication.exit(context, () -> code));
    }
}
