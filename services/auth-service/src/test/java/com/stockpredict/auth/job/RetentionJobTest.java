package com.stockpredict.auth.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stockpredict.auth.config.AuthJwtProperties;
import com.stockpredict.auth.repository.LoginAttemptRepository;
import com.stockpredict.auth.repository.RefreshTokenRepository;
import com.stockpredict.common.time.MarketClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RetentionJobTest {

    private static final Instant NOW = Instant.parse("2026-06-10T12:00:00Z");

    @Mock
    private LoginAttemptRepository loginAttemptRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private RetentionJob job;

    @BeforeEach
    void setUp() {
        AuthJwtProperties properties = new AuthJwtProperties();
        properties.setLoginRetentionDays(30);
        properties.setTokenRetentionDays(7);
        job = new RetentionJob(loginAttemptRepository, refreshTokenRepository, properties,
                new MarketClock(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    @Test
    void deletesRowsOlderThanConfiguredCutoffs() {
        when(loginAttemptRepository.deleteByAttemptedAtBefore(any())).thenReturn(3);
        when(refreshTokenRepository.deleteExpiredOrRevokedBefore(any())).thenReturn(5);

        job.prune();

        ArgumentCaptor<Instant> loginCutoff = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> tokenCutoff = ArgumentCaptor.forClass(Instant.class);
        verify(loginAttemptRepository).deleteByAttemptedAtBefore(loginCutoff.capture());
        verify(refreshTokenRepository).deleteExpiredOrRevokedBefore(tokenCutoff.capture());
        assertThat(loginCutoff.getValue()).isEqualTo(NOW.minus(Duration.ofDays(30)));
        assertThat(tokenCutoff.getValue()).isEqualTo(NOW.minus(Duration.ofDays(7)));
    }

    @Test
    void swallowsRepositoryFailures() {
        when(loginAttemptRepository.deleteByAttemptedAtBefore(any()))
                .thenThrow(new RuntimeException("database unavailable"));

        job.prune();

        verify(refreshTokenRepository, never()).deleteExpiredOrRevokedBefore(any());
    }
}
