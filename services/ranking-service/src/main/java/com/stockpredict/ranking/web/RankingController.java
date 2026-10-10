package com.stockpredict.ranking.web;

import com.stockpredict.ranking.api.RecommendationsApi;
import com.stockpredict.ranking.api.model.LongTermRanking;
import com.stockpredict.ranking.service.RankingQueryService;
import java.time.LocalDate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Implements the frozen long-term ranking contract. */
@RestController
public class RankingController implements RecommendationsApi {

    private final RankingQueryService queryService;

    public RankingController(RankingQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public ResponseEntity<LongTermRanking> getLongTermRecommendations(Integer limit, LocalDate date) {
        return ResponseEntity.ok(queryService.getLongTermRecommendations(limit, date));
    }
}
