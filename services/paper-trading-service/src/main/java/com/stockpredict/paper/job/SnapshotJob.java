package com.stockpredict.paper.job;

import com.stockpredict.paper.service.SnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Writes end-of-day equity snapshots after the close. */
@Component
public class SnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(SnapshotJob.class);

    private final SnapshotService snapshotService;

    public SnapshotJob(SnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @Scheduled(cron = "${stockpredict.paper.snapshot-cron:0 5 16 * * MON-FRI}", zone = "America/New_York")
    public void snapshot() {
        int written = snapshotService.snapshotAll();
        if (written > 0) {
            log.info("Wrote {} equity snapshot(s)", written);
        }
    }
}
