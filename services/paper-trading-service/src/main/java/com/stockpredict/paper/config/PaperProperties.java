package com.stockpredict.paper.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Paper-trading settings ({@code stockpredict.paper.*}). */
@ConfigurationProperties(prefix = "stockpredict.paper")
public class PaperProperties {

    private double startingCash = 100000;

    private int freePlanWatchlistLimit = 10;

    private int watchlistSwapLockDays = 30;

    private int slippageBps = 5;

    private boolean allowAfterHours = false;

    private long matchIntervalMs = 5000;

    private String marketDataUrl = "http://localhost:8082";

    private long quoteTimeoutMs = 1500;

    private String snapshotCron = "0 5 16 * * MON-FRI";

    private String expiryCron = "0 5 16 * * MON-FRI";

    private boolean runMatchOnce = false;

    public double getStartingCash() {
        return startingCash;
    }

    public void setStartingCash(double startingCash) {
        this.startingCash = startingCash;
    }

    public int getFreePlanWatchlistLimit() {
        return freePlanWatchlistLimit;
    }

    public void setFreePlanWatchlistLimit(int freePlanWatchlistLimit) {
        this.freePlanWatchlistLimit = freePlanWatchlistLimit;
    }

    public int getWatchlistSwapLockDays() {
        return watchlistSwapLockDays;
    }

    public void setWatchlistSwapLockDays(int watchlistSwapLockDays) {
        this.watchlistSwapLockDays = watchlistSwapLockDays;
    }

    public int getSlippageBps() {
        return slippageBps;
    }

    public void setSlippageBps(int slippageBps) {
        this.slippageBps = slippageBps;
    }

    public boolean isAllowAfterHours() {
        return allowAfterHours;
    }

    public void setAllowAfterHours(boolean allowAfterHours) {
        this.allowAfterHours = allowAfterHours;
    }

    public long getMatchIntervalMs() {
        return matchIntervalMs;
    }

    public void setMatchIntervalMs(long matchIntervalMs) {
        this.matchIntervalMs = matchIntervalMs;
    }

    public String getMarketDataUrl() {
        return marketDataUrl;
    }

    public void setMarketDataUrl(String marketDataUrl) {
        this.marketDataUrl = marketDataUrl;
    }

    public long getQuoteTimeoutMs() {
        return quoteTimeoutMs;
    }

    public void setQuoteTimeoutMs(long quoteTimeoutMs) {
        this.quoteTimeoutMs = quoteTimeoutMs;
    }

    public String getSnapshotCron() {
        return snapshotCron;
    }

    public void setSnapshotCron(String snapshotCron) {
        this.snapshotCron = snapshotCron;
    }

    public String getExpiryCron() {
        return expiryCron;
    }

    public void setExpiryCron(String expiryCron) {
        this.expiryCron = expiryCron;
    }

    public boolean isRunMatchOnce() {
        return runMatchOnce;
    }

    public void setRunMatchOnce(boolean runMatchOnce) {
        this.runMatchOnce = runMatchOnce;
    }
}
