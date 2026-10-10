package com.stockpredict.prediction.batch;

import com.stockpredict.prediction.service.BatchResult;
import com.stockpredict.prediction.service.PredictionBatchService;
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
 * {@code stockpredict.predictions.run-batch-once=true} runs the batch and exits
 * with code 0/1 instead of starting a long-lived API server.
 */
@Component
@ConditionalOnProperty(prefix = "stockpredict.predictions", name = "run-batch-once", havingValue = "true")
public class BatchRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BatchRunner.class);

    private final PredictionBatchService batchService;
    private final ApplicationContext context;

    public BatchRunner(PredictionBatchService batchService, ApplicationContext context) {
        this.batchService = batchService;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = 0;
        try {
            BatchResult result = batchService.run();
            log.info("One-shot prediction batch complete: {}", result);
        } catch (Exception ex) {
            log.error("One-shot prediction batch failed", ex);
            exitCode = 1;
        }
        int code = exitCode;
        System.exit(SpringApplication.exit(context, () -> code));
    }
}
