package com.stockpredict.feature.engine;

import java.util.List;
import java.util.Map;

/**
 * Engine configuration mirroring {@code STOCKMARKETANALYSIS/config.py} settings
 * that affect feature values. Defaults match the Python pipeline.
 */
public final class EngineConfig {

    private final List<Integer> horizons;
    private final int predictionDelay;
    private final List<String> marketSymbols;
    private final List<String> sectorEtfs;
    private final boolean residualizeFeatures;
    private final boolean useExcessReturnTarget;
    private final Map<String, String> sectorBySymbol;

    private EngineConfig(Builder builder) {
        this.horizons = List.copyOf(builder.horizons);
        this.predictionDelay = builder.predictionDelay;
        this.marketSymbols = List.copyOf(builder.marketSymbols);
        this.sectorEtfs = List.copyOf(builder.sectorEtfs);
        this.residualizeFeatures = builder.residualizeFeatures;
        this.useExcessReturnTarget = builder.useExcessReturnTarget;
        this.sectorBySymbol = Map.copyOf(builder.sectorBySymbol);
    }

    public static EngineConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<Integer> horizons() {
        return horizons;
    }

    public int predictionDelay() {
        return predictionDelay;
    }

    public List<String> marketSymbols() {
        return marketSymbols;
    }

    public List<String> sectorEtfs() {
        return sectorEtfs;
    }

    public boolean residualizeFeatures() {
        return residualizeFeatures;
    }

    public boolean useExcessReturnTarget() {
        return useExcessReturnTarget;
    }

    public Map<String, String> sectorBySymbol() {
        return sectorBySymbol;
    }

    public static final class Builder {
        private List<Integer> horizons = List.of(1, 5, 20);
        private int predictionDelay = 1;
        private List<String> marketSymbols = List.of("SPY", "QQQ", "IWM");
        private List<String> sectorEtfs = List.of(
                "XLK", "XLF", "XLE", "XLI", "XLP", "XLU", "XLV", "XLY", "XLB", "XLRE", "XLC");
        private boolean residualizeFeatures = true;
        private boolean useExcessReturnTarget = true;
        private Map<String, String> sectorBySymbol = Map.of();

        public Builder horizons(List<Integer> horizons) {
            this.horizons = horizons;
            return this;
        }

        public Builder predictionDelay(int predictionDelay) {
            this.predictionDelay = predictionDelay;
            return this;
        }

        public Builder marketSymbols(List<String> marketSymbols) {
            this.marketSymbols = marketSymbols;
            return this;
        }

        public Builder sectorEtfs(List<String> sectorEtfs) {
            this.sectorEtfs = sectorEtfs;
            return this;
        }

        public Builder residualizeFeatures(boolean residualizeFeatures) {
            this.residualizeFeatures = residualizeFeatures;
            return this;
        }

        public Builder useExcessReturnTarget(boolean useExcessReturnTarget) {
            this.useExcessReturnTarget = useExcessReturnTarget;
            return this;
        }

        public Builder sectorBySymbol(Map<String, String> sectorBySymbol) {
            this.sectorBySymbol = sectorBySymbol;
            return this;
        }

        public EngineConfig build() {
            return new EngineConfig(this);
        }
    }
}
