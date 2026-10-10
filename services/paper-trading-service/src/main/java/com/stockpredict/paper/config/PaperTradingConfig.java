package com.stockpredict.paper.config;

import com.stockpredict.paper.engine.CompositeQuoteProvider;
import com.stockpredict.paper.engine.DatabaseQuoteProvider;
import com.stockpredict.paper.engine.MarketDataQuoteClient;
import com.stockpredict.paper.engine.QuoteProvider;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.StringUtils;

/** Wiring for the paper-trading service. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(PaperProperties.class)
public class PaperTradingConfig {

    /** HTTP quote client first, latest daily close as fallback. */
    @Bean
    public QuoteProvider quoteProvider(JdbcTemplate jdbcTemplate, PaperProperties properties) {
        List<QuoteProvider> providers = new ArrayList<>();
        if (StringUtils.hasText(properties.getMarketDataUrl())) {
            providers.add(new MarketDataQuoteClient(properties.getMarketDataUrl(), properties.getQuoteTimeoutMs()));
        }
        providers.add(new DatabaseQuoteProvider(jdbcTemplate));
        return new CompositeQuoteProvider(providers);
    }
}
