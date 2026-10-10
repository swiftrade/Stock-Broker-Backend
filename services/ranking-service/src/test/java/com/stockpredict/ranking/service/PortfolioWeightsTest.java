package com.stockpredict.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.stockpredict.ranking.service.PortfolioWeights.Candidate;
import com.stockpredict.ranking.service.PortfolioWeights.Weighted;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PortfolioWeightsTest {

    private static Candidate candidate(String symbol, double predicted, double vol, String sector) {
        return new Candidate(symbol, predicted, vol, sector);
    }

    @Test
    void tenNamesWithEqualVolatilityGetTenPercentEach() {
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            candidates.add(candidate("S" + i, 0.05 - i * 0.001, 0.20, "Sector" + i));
        }

        List<Weighted> weighted = PortfolioWeights.inverseVolatility(candidates, 0.10, 0.25, 0.01);

        assertThat(weighted).hasSize(10);
        assertThat(weighted.stream().mapToDouble(Weighted::weight).sum()).isCloseTo(1.0, within(1e-9));
        assertThat(weighted).allSatisfy(pick -> assertThat(pick.weight()).isLessThanOrEqualTo(0.10 + 1e-9));
    }

    @Test
    void lowerVolatilityGetsMoreWeight() {
        List<Candidate> candidates = List.of(
                candidate("LOWVOL", 0.05, 0.10, "A"),
                candidate("HIGHVOL", 0.05, 0.40, "B"));

        List<Weighted> weighted = PortfolioWeights.inverseVolatility(candidates, 1.0, 1.0, 0.0);

        Map<String, Double> bySymbol = weighted.stream()
                .collect(Collectors.toMap(Weighted::symbol, Weighted::weight));
        assertThat(bySymbol.get("LOWVOL")).isGreaterThan(bySymbol.get("HIGHVOL"));
        assertThat(bySymbol.get("LOWVOL")).isCloseTo(0.8, within(1e-9));
    }

    @Test
    void sectorCapIsEnforced() {
        // 12 names, 6 in Tech (50% before capping), equal vols, 25% sector cap.
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            candidates.add(candidate("TECH" + i, 0.05, 0.20, "Tech"));
        }
        for (int i = 0; i < 6; i++) {
            candidates.add(candidate("OTHER" + i, 0.05, 0.20, "Sector" + i));
        }

        List<Weighted> weighted = PortfolioWeights.inverseVolatility(candidates, 0.25, 0.25, 0.0);

        double techTotal = weighted.stream()
                .filter(pick -> "Tech".equals(pick.sector()))
                .mapToDouble(Weighted::weight)
                .sum();
        assertThat(techTotal).isLessThanOrEqualTo(0.25 + 1e-4);
        assertThat(weighted.stream().mapToDouble(Weighted::weight).sum()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void dustPositionsAreDroppedAndWeightsRenormalized() {
        List<Candidate> candidates = List.of(
                candidate("CORE1", 0.05, 0.10, "A"),
                candidate("CORE2", 0.05, 0.10, "B"),
                candidate("WILD", 0.05, 1000.0, "C"));

        List<Weighted> weighted = PortfolioWeights.inverseVolatility(candidates, 1.0, 1.0, 0.01);

        assertThat(weighted).extracting(Weighted::symbol).containsExactly("CORE1", "CORE2");
        assertThat(weighted.stream().mapToDouble(Weighted::weight).sum()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void dustDropDoesNotBreachCapsItJustEnforced() {
        // Tech converges to exactly 25%; a 0.5%-weight dust name elsewhere is
        // dropped and renormalized. Without re-enforcement Tech would become
        // 0.25 / 0.995 = 25.13% > the sector cap (M7).
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            candidates.add(candidate("TECH" + i, 0.05, 0.20, "Tech"));
        }
        for (int i = 0; i < 6; i++) {
            candidates.add(candidate("OTHER" + i, 0.05, 0.20, "Sector" + i));
        }
        candidates.add(candidate("DUST", 0.05, 3.316, "Dust"));

        List<Weighted> weighted = PortfolioWeights.inverseVolatility(candidates, 1.0, 0.25, 0.01);

        double techTotal = weighted.stream()
                .filter(pick -> "Tech".equals(pick.sector()))
                .mapToDouble(Weighted::weight)
                .sum();
        assertThat(techTotal).isLessThanOrEqualTo(0.25 + 1e-4);
        assertThat(weighted).extracting(Weighted::symbol).doesNotContain("DUST");
        assertThat(weighted.stream().mapToDouble(Weighted::weight).sum()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void positionCapIsSkippedWhenInfeasible() {
        // 3 names cannot all be <= 10%; weights stay equal and sum to 1.
        List<Candidate> candidates = List.of(
                candidate("A", 0.05, 0.2, "X"),
                candidate("B", 0.05, 0.2, "Y"),
                candidate("C", 0.05, 0.2, "Z"));

        List<Weighted> weighted = PortfolioWeights.inverseVolatility(candidates, 0.10, 1.0, 0.0);

        assertThat(weighted).hasSize(3);
        assertThat(weighted).allSatisfy(pick -> assertThat(pick.weight()).isCloseTo(1.0 / 3.0, within(1e-9)));
    }

    @Test
    void missingVolatilityFallsBackToMedian() {
        List<Candidate> candidates = List.of(
                candidate("A", 0.05, 0.20, "X"),
                candidate("B", 0.05, 0.20, "Y"),
                candidate("C", 0.05, Double.NaN, "Z"));

        List<Weighted> weighted = PortfolioWeights.inverseVolatility(candidates, 1.0, 1.0, 0.0);

        assertThat(weighted).hasSize(3);
        assertThat(weighted).allSatisfy(pick -> assertThat(pick.weight()).isCloseTo(1.0 / 3.0, within(1e-9)));
    }
}
