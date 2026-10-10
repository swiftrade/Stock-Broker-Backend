package com.stockpredict.auth.job;

import com.stockpredict.auth.config.AuthJwtProperties;
import com.stockpredict.auth.repository.LoginAttemptRepository;
import com.stockpredict.auth.repository.RefreshTokenRepository;
import com.stockpredict.common.time.MarketClock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Prunes old login attempts and spent refresh tokens so auth tables stay bounded. */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final LoginAttemptRepository loginAttemptRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuthJwtProperties properties;
    private final MarketClock clock;

    public RetentionJob(LoginAttemptRepository loginAttemptRepository,
                        RefreshTokenRepository refreshTokenRepository,
                        AuthJwtProperties properties,
                        MarketClock clock) {
        this.loginAttemptRepository = loginAttemptRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${stockpredict.auth.retention-cron:0 30 3 * * *}", zone = "America/New_York")
    public void prune() {
        try {
            Instant now = clock.now();
            int attempts = loginAttemptRepository.deleteByAttemptedAtBefore(
                    now.minus(Duration.ofDays(properties.getLoginRetentionDays())));
            int tokens = refreshTokenRepository.deleteExpiredOrRevokedBefore(
                    now.minus(Duration.ofDays(properties.getTokenRetentionDays())));
            if (attempts > 0 || tokens > 0) {
                log.info("Retention pruned {} login attempt(s) and {} refresh token(s)", attempts, tokens);
            }
        } catch (RuntimeException e) {
            log.error("Auth retention job failed", e);
        }
    }
}
