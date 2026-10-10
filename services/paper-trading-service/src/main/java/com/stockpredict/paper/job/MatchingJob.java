package com.stockpredict.paper.job;

import com.stockpredict.paper.engine.MatchingEngine;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls working orders and matches them against the latest quotes. */
@Component
public class MatchingJob {

    private static final Logger log = LoggerFactory.getLogger(MatchingJob.class);

    private final MatchingEngine matchingEngine;
    private final MeterRegistry meterRegistry;

    public MatchingJob(MatchingEngine matchingEngine, MeterRegistry meterRegistry) {
        this.matchingEngine = matchingEngine;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${stockpredict.paper.match-interval-ms:5000}")
    public void match() {
        try {
            int fills = matchingEngine.matchWorkingOrders();
            if (fills > 0) {
                meterRegistry.counter("stockpredict.paper.fills").increment(fills);
                log.info("Matching poller applied {} fill(s)", fills);
            }
        } catch (RuntimeException ex) {
            log.error("Matching poller failed", ex);
        }
    }
}
