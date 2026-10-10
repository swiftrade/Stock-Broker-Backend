package com.stockpredict.paper.job;

import com.stockpredict.common.time.MarketClock;
import com.stockpredict.paper.data.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Expires DAY orders whose session has ended. */
@Component
public class DayOrderExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(DayOrderExpiryJob.class);

    private final OrderRepository orderRepository;
    private final MarketClock clock;

    public DayOrderExpiryJob(OrderRepository orderRepository, MarketClock clock) {
        this.orderRepository = orderRepository;
        this.clock = clock;
    }

    @Scheduled(cron = "${stockpredict.paper.expiry-cron:0 5 16 * * MON-FRI}", zone = "America/New_York")
    public void expireDayOrders() {
        int expired = orderRepository.expireDue(clock.now());
        if (expired > 0) {
            log.info("Expired {} DAY order(s)", expired);
        }
    }
}
