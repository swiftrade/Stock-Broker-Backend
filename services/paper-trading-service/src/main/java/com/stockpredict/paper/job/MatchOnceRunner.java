package com.stockpredict.paper.job;

import com.stockpredict.paper.engine.MatchingEngine;
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
 * {@code stockpredict.paper.run-match-once=true} runs the matching poller once
 * and exits 0/1.
 */
@Component
@ConditionalOnProperty(prefix = "stockpredict.paper", name = "run-match-once", havingValue = "true")
public class MatchOnceRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MatchOnceRunner.class);

    private final MatchingEngine matchingEngine;
    private final ApplicationContext context;

    public MatchOnceRunner(MatchingEngine matchingEngine, ApplicationContext context) {
        this.matchingEngine = matchingEngine;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = 0;
        try {
            int fills = matchingEngine.matchWorkingOrders();
            log.info("One-shot matching complete: {} fill(s)", fills);
        } catch (RuntimeException ex) {
            log.error("One-shot matching failed", ex);
            exitCode = 1;
        }
        int code = exitCode;
        System.exit(SpringApplication.exit(context, () -> code));
    }
}
