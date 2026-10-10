package com.stockpredict.prediction.config;

import com.stockpredict.feature.engine.EngineConfig;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(PredictionProperties.class)
public class PredictionConfig {

    /** Feature semantics must match the Python training pipeline exactly. */
    @Bean
    public EngineConfig engineConfig(PredictionProperties properties) {
        return EngineConfig.builder()
                // Configurable so the engine's target_* columns track the exported
                // model metadata instead of a hard-coded list (L8).
                .horizons(properties.getHorizons())
                .predictionDelay(properties.getPredictionDelay())
                .marketSymbols(properties.getMarketSymbols())
                .sectorEtfs(properties.getSectorEtfs())
                .residualizeFeatures(properties.isResidualizeFeatures())
                .useExcessReturnTarget(properties.isUseExcessReturnTarget())
                .build();
    }
}
