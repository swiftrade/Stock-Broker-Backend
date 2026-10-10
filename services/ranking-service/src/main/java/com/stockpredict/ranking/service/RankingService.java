package com.stockpredict.ranking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpredict.common.error.ApiException;
import com.stockpredict.common.error.ErrorCode;
import com.stockpredict.common.time.MarketClock;
import com.stockpredict.ranking.config.RankingProperties;
import com.stockpredict.ranking.data.PredictionSource;
import com.stockpredict.ranking.data.PredictionSource.PredictionSnapshot;
import com.stockpredict.ranking.data.RankingRepository;
import com.stockpredict.ranking.data.RankingRow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nightly job: take the latest 20d predictions, size the top N with
 * inverse-volatility weights under position/sector caps, and persist the run
 * (history is retained for transparency).
 */
@Service
public class RankingService {

    private static final Logger log = LoggerFactory.getLogger(RankingService.class);
    private static final int HORIZON_DAYS = 20;
    private static final List<String> RATIONALE_FEATURES = List.of(
            "log_return_20d", "resid_return_20d", "volatility_20d", "rsi_14", "log_return_20d_rank");

    private final PredictionSource predictionSource;
    private final RankingRepository rankingRepository;
    private final SectorProvider sectorProvider;
    private final RankingProperties properties;
    private final MarketClock clock;
    private final ObjectMapper objectMapper;

    public RankingService(PredictionSource predictionSource,
                          RankingRepository rankingRepository,
                          SectorProvider sectorProvider,
                          RankingProperties properties,
                          MarketClock clock,
                          ObjectMapper objectMapper) {
        this.predictionSource = predictionSource;
        this.rankingRepository = rankingRepository;
        this.sectorProvider = sectorProvider;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RankingResult run() {
        LocalDate runDate = clock.todayEt();
        List<PredictionSnapshot> candidates = predictionSource.topByPredictedReturn(
                HORIZON_DAYS, properties.getCandidatePool());
        if (candidates.isEmpty()) {
            throw new ApiException(ErrorCode.UNPROCESSABLE,
                    "No 20d predictions available; run the prediction batch first");
        }
        List<PredictionSnapshot> selected = candidates.stream().limit(properties.getTopN()).toList();
        LocalDate asOf = predictionSource.latestAsOf(HORIZON_DAYS).orElse(runDate);
        List<String> symbols = selected.stream().map(PredictionSnapshot::symbol).toList();
        Map<String, Double> volatility = predictionSource.realizedVolatility(
                symbols, asOf, properties.getVolatilityWindow());

        List<PortfolioWeights.Candidate> sizing = new ArrayList<>(selected.size());
        Map<String, String> featuresBySymbol = new LinkedHashMap<>();
        for (PredictionSnapshot snapshot : selected) {
            featuresBySymbol.put(snapshot.symbol(), snapshot.featuresJson());
            sizing.add(new PortfolioWeights.Candidate(
                    snapshot.symbol(),
                    snapshot.predictedLogReturn(),
                    volatility.getOrDefault(snapshot.symbol(), Double.NaN),
                    sectorProvider.sector(snapshot.symbol())));
        }
        List<PortfolioWeights.Weighted> weighted = PortfolioWeights.inverseVolatility(
                sizing, properties.getMaxPositionWeight(),
                properties.getMaxSectorWeight(), properties.getMinPositionWeight());

        Map<String, Integer> previousRanks = rankingRepository.previousRanks(runDate);
        String modelVersion = selected.get(0).modelVersion();
        List<RankingRow> rows = new ArrayList<>(weighted.size());
        for (int i = 0; i < weighted.size(); i++) {
            PortfolioWeights.Weighted pick = weighted.get(i);
            int rank = i + 1;
            Integer previousRank = previousRanks.get(pick.symbol());
            Integer rankChange = previousRank == null ? null : previousRank - rank;
            rows.add(new RankingRow(runDate, modelVersion, pick.symbol(), rank, rankChange,
                    pick.predictedReturn(), Double.isFinite(pick.volatility()) ? pick.volatility() : null,
                    pick.sector(), pick.weight(),
                    rationaleJson(featuresBySymbol.get(pick.symbol()))));
        }
        rankingRepository.upsertAll(rows);
        log.info("Ranking run {}: {} picks, weight sum {}", runDate, rows.size(),
                rows.stream().mapToDouble(RankingRow::weight).sum());
        return new RankingResult(runDate, modelVersion, rows.size());
    }

    private String rationaleJson(String featuresJson) {
        Map<String, Object> rationale = new LinkedHashMap<>();
        if (featuresJson != null && !featuresJson.isBlank()) {
            try {
                Map<String, Object> features = objectMapper.readValue(featuresJson, Map.class);
                for (String feature : RATIONALE_FEATURES) {
                    Object value = features.get(feature);
                    if (value != null) {
                        rationale.put(feature, value);
                    }
                }
            } catch (Exception ex) {
                log.warn("Unable to parse features for rationale: {}", ex.getMessage());
            }
        }
        try {
            return objectMapper.writeValueAsString(rationale);
        } catch (Exception ex) {
            return "{}";
        }
    }
}
