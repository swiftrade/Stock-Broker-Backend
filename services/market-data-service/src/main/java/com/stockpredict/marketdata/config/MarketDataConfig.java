package com.stockpredict.marketdata.config;

import com.stockpredict.common.time.MarketClock;
import com.stockpredict.common.time.TradingCalendar;
import com.stockpredict.marketdata.provider.MarketDataProvider;
import com.stockpredict.marketdata.provider.PolygonProvider;
import com.stockpredict.marketdata.provider.ProviderResilience;
import com.stockpredict.marketdata.provider.ResilientMarketDataProvider;
import com.stockpredict.marketdata.provider.SyntheticProvider;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/** Wires the provider adapter chosen by {@code stockpredict.market-data.provider}. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(MarketDataProperties.class)
public class MarketDataConfig {

    private static final Logger log = LoggerFactory.getLogger(MarketDataConfig.class);
    private static final String POLYGON_BASE_URL = "https://api.polygon.io";

    @Bean
    @ConditionalOnMissingBean
    public RestClient polygonRestClient(MarketDataProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getPolygonConnectTimeout());
        requestFactory.setReadTimeout(properties.getPolygonReadTimeout());
        return RestClient.builder()
                .baseUrl(POLYGON_BASE_URL)
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public ProviderResilience providerResilience() {
        return new ProviderResilience("market-data-provider");
    }

    @Bean
    @ConditionalOnMissingBean
    public MarketDataProvider marketDataProvider(MarketDataProperties properties,
                                                 ProviderResilience resilience,
                                                 RestClient polygonRestClient,
                                                 MarketClock clock,
                                                 TradingCalendar calendar) {
        MarketDataProvider delegate = selectDelegate(properties, polygonRestClient, clock, calendar);
        log.info("Market-data provider: {}", delegate.source());
        return new ResilientMarketDataProvider(delegate, resilience);
    }

    private MarketDataProvider selectDelegate(MarketDataProperties properties,
                                              RestClient polygonRestClient,
                                              MarketClock clock,
                                              TradingCalendar calendar) {
        String mode = properties.getProvider() == null
                ? "auto"
                : properties.getProvider().trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "polygon" -> new PolygonProvider(polygonRestClient, properties.getPolygonApiKey(), clock);
            case "synthetic" -> new SyntheticProvider(clock, calendar);
            default -> StringUtils.hasText(properties.getPolygonApiKey())
                    ? new PolygonProvider(polygonRestClient, properties.getPolygonApiKey(), clock)
                    : new SyntheticProvider(clock, calendar);
        };
    }
}
