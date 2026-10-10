package com.stockpredict.ranking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Ranking-service settings ({@code stockpredict.ranking.*}). */
@ConfigurationProperties(prefix = "stockpredict.ranking")
public class RankingProperties {

    private int topN = 10;

    /** Names considered for sizing before position/sector caps. */
    private int candidatePool = 50;

    private double maxPositionWeight = 0.10;

    private double maxSectorWeight = 0.25;

    private double minPositionWeight = 0.01;

    /** Trading days used for the realized-volatility estimate. */
    private int volatilityWindow = 20;

    private String disclaimer = "Research signal only. Not investment advice.";

    /** Optional JSON map of symbol -> GICS sector, exported from the Python pipeline. */
    private String sectorMapFile = "config/sectors.json";

    private String batchCron = "0 0 2 * * TUE-SAT";

    private boolean runBatchOnce = false;

    public int getTopN() {
        return topN;
    }

    public void setTopN(int topN) {
        this.topN = topN;
    }

    public int getCandidatePool() {
        return candidatePool;
    }

    public void setCandidatePool(int candidatePool) {
        this.candidatePool = candidatePool;
    }

    public double getMaxPositionWeight() {
        return maxPositionWeight;
    }

    public void setMaxPositionWeight(double maxPositionWeight) {
        this.maxPositionWeight = maxPositionWeight;
    }

    public double getMaxSectorWeight() {
        return maxSectorWeight;
    }

    public void setMaxSectorWeight(double maxSectorWeight) {
        this.maxSectorWeight = maxSectorWeight;
    }

    public double getMinPositionWeight() {
        return minPositionWeight;
    }

    public void setMinPositionWeight(double minPositionWeight) {
        this.minPositionWeight = minPositionWeight;
    }

    public int getVolatilityWindow() {
        return volatilityWindow;
    }

    public void setVolatilityWindow(int volatilityWindow) {
        this.volatilityWindow = volatilityWindow;
    }

    public String getDisclaimer() {
        return disclaimer;
    }

    public void setDisclaimer(String disclaimer) {
        this.disclaimer = disclaimer;
    }

    public String getSectorMapFile() {
        return sectorMapFile;
    }

    public void setSectorMapFile(String sectorMapFile) {
        this.sectorMapFile = sectorMapFile;
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
}
