package com.stockpredict.prediction.web;

import com.stockpredict.common.plan.Plan;
import com.stockpredict.common.security.JwtClaims;
import com.stockpredict.prediction.api.PredictionsApi;
import com.stockpredict.prediction.api.model.ModelInfo;
import com.stockpredict.prediction.api.model.PredictionList;
import com.stockpredict.prediction.api.model.SymbolPrediction;
import com.stockpredict.prediction.service.PredictionQueryService;
import java.time.LocalDate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

/** Implements the frozen prediction contract; reads only precomputed rows. */
@RestController
public class PredictionController implements PredictionsApi {

    private final PredictionQueryService queryService;

    public PredictionController(PredictionQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public ResponseEntity<PredictionList> getIntradayPredictions(LocalDate date, Integer limit, String sort) {
        return ResponseEntity.ok(queryService.getIntradayPredictions(date, limit, sort, currentPlan()));
    }

    @Override
    public ResponseEntity<SymbolPrediction> getSymbolPrediction(String symbol) {
        return ResponseEntity.ok(queryService.getSymbolPrediction(symbol));
    }

    @Override
    public ResponseEntity<ModelInfo> getModelInfo() {
        return ResponseEntity.ok(queryService.getModelInfo());
    }

    /** Plan comes from the verified JWT; anonymous callers are treated as FREE. */
    private static Plan currentPlan() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof JwtClaims claims) {
            return claims.plan();
        }
        return Plan.FREE;
    }
}
