package com.stockpredict.marketdata.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Market-data-service settings ({@code stockpredict.market-data.*}). */
@ConfigurationProperties(prefix = "stockpredict.market-data")
public class MarketDataProperties {

    /** {@code auto} picks polygon when an API key is present, else synthetic. */
    private String provider = "auto";

    private String polygonApiKey = "";

    private Duration polygonConnectTimeout = Duration.ofSeconds(5);

    private Duration polygonReadTimeout = Duration.ofSeconds(30);

    private int dailyBackfillYears = 5;

    private int intradayDays = 30;

    private String dailyCron = "0 15 6 * * TUE-SAT";

    private boolean dailySchedulingEnabled = true;

    private boolean runIngestOnce = false;

    private int quoteCacheTtlSeconds = 60;

    /**
     * Allows cache-miss history reads to fetch and persist bars, but only for
     * symbols already known to the service (universe member or existing bars).
     * Never registers the symbol in the ingestion universe.
     */
    private boolean allowOnDemandIngestion = true;

    private long streamMaxLength = 100_000L;

    private List<String> fallbackSymbols = List.of(
            "AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "TSLA", "BRK.B", "JPM", "V");

    private List<String> marketSymbols = List.of("SPY", "QQQ", "IWM");

    private List<String> sectorEtfs = List.of(
            "XLK", "XLF", "XLE", "XLI", "XLP", "XLU", "XLV", "XLY", "XLB", "XLRE", "XLC");

    private Guard guard = new Guard();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getPolygonApiKey() {
        return polygonApiKey;
    }

    public void setPolygonApiKey(String polygonApiKey) {
        this.polygonApiKey = polygonApiKey;
    }

    public Duration getPolygonConnectTimeout() {
        return polygonConnectTimeout;
    }

    public void setPolygonConnectTimeout(Duration polygonConnectTimeout) {
        this.polygonConnectTimeout = polygonConnectTimeout;
    }

    public Duration getPolygonReadTimeout() {
        return polygonReadTimeout;
    }

    public void setPolygonReadTimeout(Duration polygonReadTimeout) {
        this.polygonReadTimeout = polygonReadTimeout;
    }

    public int getDailyBackfillYears() {
        return dailyBackfillYears;
    }

    public void setDailyBackfillYears(int dailyBackfillYears) {
        this.dailyBackfillYears = dailyBackfillYears;
    }

    public int getIntradayDays() {
        return intradayDays;
    }

    public void setIntradayDays(int intradayDays) {
        this.intradayDays = intradayDays;
    }

    public String getDailyCron() {
        return dailyCron;
    }

    public void setDailyCron(String dailyCron) {
        this.dailyCron = dailyCron;
    }

    public boolean isDailySchedulingEnabled() {
        return dailySchedulingEnabled;
    }

    public void setDailySchedulingEnabled(boolean dailySchedulingEnabled) {
        this.dailySchedulingEnabled = dailySchedulingEnabled;
    }

    public boolean isRunIngestOnce() {
        return runIngestOnce;
    }

    public void setRunIngestOnce(boolean runIngestOnce) {
        this.runIngestOnce = runIngestOnce;
    }

    public int getQuoteCacheTtlSeconds() {
        return quoteCacheTtlSeconds;
    }

    public void setQuoteCacheTtlSeconds(int quoteCacheTtlSeconds) {
        this.quoteCacheTtlSeconds = quoteCacheTtlSeconds;
    }

    public boolean isAllowOnDemandIngestion() {
        return allowOnDemandIngestion;
    }

    public void setAllowOnDemandIngestion(boolean allowOnDemandIngestion) {
        this.allowOnDemandIngestion = allowOnDemandIngestion;
    }

    public long getStreamMaxLength() {
        return streamMaxLength;
    }

    public void setStreamMaxLength(long streamMaxLength) {
        this.streamMaxLength = streamMaxLength;
    }

    public List<String> getFallbackSymbols() {
        return fallbackSymbols;
    }

    public void setFallbackSymbols(List<String> fallbackSymbols) {
        this.fallbackSymbols = fallbackSymbols;
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

    public Guard getGuard() {
        return guard;
    }

    public void setGuard(Guard guard) {
        this.guard = guard;
    }

    /** Quality-guard thresholds ({@code stockpredict.market-data.guard.*}). */
    public static class Guard {

        /** Bars newer than now + tolerance are rejected as future data. */
        private Duration futureTolerance = Duration.ofMinutes(5);

        /** Intraday bars older than now - staleness are rejected when live. */
        private Duration intradayStaleness = Duration.ofMinutes(30);

        /** Close-to-close moves beyond this fraction are accepted but flagged. */
        private double maxDeviationPct = 0.35;

        /**
         * Relative bid/ask spread above which a provider quote is rejected as
         * unusable (protects the paper matching engine from garbage quotes).
         */
        private double maxQuoteSpreadRatio = 0.5;

        public Duration getFutureTolerance() {
            return futureTolerance;
        }

        public void setFutureTolerance(Duration futureTolerance) {
            this.futureTolerance = futureTolerance;
        }

        public Duration getIntradayStaleness() {
            return intradayStaleness;
        }

        public void setIntradayStaleness(Duration intradayStaleness) {
            this.intradayStaleness = intradayStaleness;
        }

        public double getMaxDeviationPct() {
            return maxDeviationPct;
        }

        public void setMaxDeviationPct(double maxDeviationPct) {
            this.maxDeviationPct = maxDeviationPct;
        }

        public double getMaxQuoteSpreadRatio() {
            return maxQuoteSpreadRatio;
        }

        public void setMaxQuoteSpreadRatio(double maxQuoteSpreadRatio) {
            this.maxQuoteSpreadRatio = maxQuoteSpreadRatio;
        }
    }
}
