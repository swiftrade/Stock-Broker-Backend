package com.stockpredict.ranking.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpredict.common.time.MarketClock;
import com.stockpredict.common.time.TradingCalendar;
import com.stockpredict.ranking.api.model.LongTermRanking;
import com.stockpredict.ranking.api.model.RankedPick;
import com.stockpredict.ranking.config.RankingProperties;
import com.stockpredict.ranking.data.RankingRepository;
import com.stockpredict.ranking.data.RankingRow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read path for long-term rankings; served from the retained run history. */
@Service
public class RankingQueryService {

    private static final Logger log = LoggerFactory.getLogger(RankingQueryService.class);

    private final RankingRepository rankingRepository;
    private final RankingProperties properties;
    private final ObjectMapper objectMapper;
    private final TradingCalendar tradingCalendar;
    private final MarketClock clock;

    public RankingQueryService(RankingRepository rankingRepository,
                               RankingProperties properties,
                               ObjectMapper objectMapper,
                               TradingCalendar tradingCalendar,
                               MarketClock clock) {
        this.rankingRepository = rankingRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.tradingCalendar = tradingCalendar;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public LongTermRanking getLongTermRecommendations(Integer limit, LocalDate date) {
        int effectiveLimit = limit == null ? properties.getTopN() : limit;
        LocalDate runDate = date != null ? date : rankingRepository.latestRunDate().orElse(null);
        if (runDate == null) {
            return new LongTermRanking()
                    .modelVersion("unknown")
                    .stale(true)
                    .disclaimer(properties.getDisclaimer())
                    .picks(List.of());
        }
        List<RankingRow> rows = rankingRepository.findByRunDate(runDate, effectiveLimit);
        List<RankedPick> picks = new ArrayList<>(rows.size());
        for (RankingRow row : rows) {
            picks.add(new RankedPick()
                    .symbol(row.symbol())
                    .rank(row.rank())
                    .rankChange(row.rankChange())
                    .predicted20dReturn(row.predicted20dReturn())
                    .volatility(row.volatility())
                    .sector(row.sector())
                    .weight(row.weight())
                    .rationale(parseRationale(row.rationaleJson())));
        }
        String modelVersion = rows.isEmpty() ? "unknown" : rows.get(0).modelVersion();
        return new LongTermRanking()
                .runDate(runDate)
                .modelVersion(modelVersion)
                .stale(runDate.isBefore(tradingCalendar.previousTradingDay(clock.todayEt())))
                .disclaimer(properties.getDisclaimer())
                .picks(picks);
    }

    private Map<String, Object> parseRationale(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception ex) {
            log.warn("Unable to parse rationale: {}", ex.getMessage());
            return Map.of();
        }
    }
}
