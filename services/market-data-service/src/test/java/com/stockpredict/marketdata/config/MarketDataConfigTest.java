package com.stockpredict.marketdata.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockpredict.common.time.MarketClock;
import com.stockpredict.common.time.TradingCalendar;
import com.stockpredict.marketdata.provider.MarketDataProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class MarketDataConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MarketDataConfig.class, TestBeans.class);

    @Test
    void autoFallsBackToSyntheticWithoutApiKey() {
        runner.withPropertyValues("stockpredict.market-data.provider=auto")
                .run(context -> {
                    assertThat(context).hasSingleBean(MarketDataProvider.class);
                    assertThat(context.getBean(MarketDataProvider.class).source()).isEqualTo("synthetic");
                });
    }

    @Test
    void autoSelectsPolygonWhenApiKeyIsPresent() {
        runner.withPropertyValues(
                        "stockpredict.market-data.provider=auto",
                        "stockpredict.market-data.polygon-api-key=test-key")
                .run(context -> assertThat(context.getBean(MarketDataProvider.class).source()).isEqualTo("polygon"));
    }

    @Test
    void explicitSyntheticWinsOverApiKey() {
        runner.withPropertyValues(
                        "stockpredict.market-data.provider=synthetic",
                        "stockpredict.market-data.polygon-api-key=test-key")
                .run(context -> assertThat(context.getBean(MarketDataProvider.class).source()).isEqualTo("synthetic"));
    }

    @Test
    void polygonTimeoutsAreBound() {
        runner.withPropertyValues(
                        "stockpredict.market-data.polygon-connect-timeout=PT3S",
                        "stockpredict.market-data.polygon-read-timeout=PT12S")
                .run(context -> {
                    MarketDataProperties properties = context.getBean(MarketDataProperties.class);
                    assertThat(properties.getPolygonConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(properties.getPolygonReadTimeout()).isEqualTo(Duration.ofSeconds(12));
                });
    }

    @Test
    void marketDataDefaultsAreBackwardCompatible() {
        runner.run(context -> {
            MarketDataProperties properties = context.getBean(MarketDataProperties.class);
            assertThat(properties.getPolygonConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.getPolygonReadTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.isAllowOnDemandIngestion()).isTrue();
            assertThat(properties.getStreamMaxLength()).isEqualTo(100_000L);
        });
    }

    @Test
    void guardThresholdsAreBound() {
        runner.withPropertyValues(
                        "stockpredict.market-data.guard.future-tolerance=PT10M",
                        "stockpredict.market-data.guard.intraday-staleness=PT45M",
                        "stockpredict.market-data.guard.max-deviation-pct=0.5")
                .run(context -> {
                    MarketDataProperties properties = context.getBean(MarketDataProperties.class);
                    assertThat(properties.getGuard().getFutureTolerance()).isEqualTo(Duration.ofMinutes(10));
                    assertThat(properties.getGuard().getIntradayStaleness()).isEqualTo(Duration.ofMinutes(45));
                    assertThat(properties.getGuard().getMaxDeviationPct()).isEqualTo(0.5);
                });
    }

    @Configuration
    static class TestBeans {

        @Bean
        MarketClock marketClock() {
            return new MarketClock(Clock.fixed(Instant.parse("2026-06-10T15:00:00Z"), ZoneOffset.UTC));
        }

        @Bean
        TradingCalendar tradingCalendar() {
            return new TradingCalendar();
        }
    }
}
