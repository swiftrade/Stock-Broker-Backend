package com.stockpredict.prediction.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Prediction-service settings ({@code stockpredict.predictions.*}). */
@ConfigurationProperties(prefix = "stockpredict.predictions")
public class PredictionProperties {

    /** Directory containing {@code manifest.json} + version directories. */
    private String modelDir = "models";

    private String disclaimer = "Research signal only. Not investment advice.";

    /** FREE plan sees at most this many ranked predictions. */
    private int freePlanLimit = 20;

    private int cacheTtlSeconds = 60;

    /** Load and blend per-stock models when the metadata provides them. */
    private boolean includePerStock = true;

    /** Calendar days of bars loaded for feature computation. */
    private int historyDays = 500;

    private int predictionDelay = 1;

    private boolean residualizeFeatures = true;

    private boolean useExcessReturnTarget = true;

    private List<String> marketSymbols = List.of("SPY", "QQQ", "IWM");

    private List<String> sectorEtfs = List.of(
            "XLK", "XLF", "XLE", "XLI", "XLP", "XLU", "XLV", "XLY", "XLB", "XLRE", "XLC");

    private String batchCron = "0 30 8 * * MON-FRI";

    private boolean runBatchOnce = false;

    /** Training horizons; must match the exported model metadata. */
    private List<Integer> horizons = List.of(1, 5, 20);

    public String getModelDir() {
        return modelDir;
    }

    public void setModelDir(String modelDir) {
        this.modelDir = modelDir;
    }

    public String getDisclaimer() {
        return disclaimer;
    }

    public void setDisclaimer(String disclaimer) {
        this.disclaimer = disclaimer;
    }

    public int getFreePlanLimit() {
        return freePlanLimit;
    }

    public void setFreePlanLimit(int freePlanLimit) {
        this.freePlanLimit = freePlanLimit;
    }

    public int getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public void setCacheTtlSeconds(int cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    public boolean isIncludePerStock() {
        return includePerStock;
    }

    public void setIncludePerStock(boolean includePerStock) {
        this.includePerStock = includePerStock;
    }

    public int getHistoryDays() {
        return historyDays;
    }

    public void setHistoryDays(int historyDays) {
        this.historyDays = historyDays;
    }

    public int getPredictionDelay() {
        return predictionDelay;
    }

    public void setPredictionDelay(int predictionDelay) {
        this.predictionDelay = predictionDelay;
    }

    public boolean isResidualizeFeatures() {
        return residualizeFeatures;
    }

    public void setResidualizeFeatures(boolean residualizeFeatures) {
        this.residualizeFeatures = residualizeFeatures;
    }

    public boolean isUseExcessReturnTarget() {
        return useExcessReturnTarget;
    }

    public void setUseExcessReturnTarget(boolean useExcessReturnTarget) {
        this.useExcessReturnTarget = useExcessReturnTarget;
    }

    public List<String> getMarketSymbols() {
        return marketSymbols;
    }

    public void setMarketSymbols(List<String> marketSymbols) {
        this.marketSymbols = marketSymbols;
    }

    public List<String> getSectorEtfs() {
        return sectorEtfs;
    }

    public void setSectorEtfs(List<String> sectorEtfs) {
        this.sectorEtfs = sectorEtfs;
    }

    public String getBatchCron() {
        return batchCron;
    }

    public void setBatchCron(String batchCron) {
        this.batchCron = batchCron;
    }

    public boolean isRunBatchOnce() {
        return runBatchOnce;
    }

    public void setRunBatchOnce(boolean runBatchOnce) {
        this.runBatchOnce = runBatchOnce;
    }

    public List<Integer> getHorizons() {
        return horizons;
    }

    public void setHorizons(List<Integer> horizons) {
        this.horizons = horizons;
    }
}
