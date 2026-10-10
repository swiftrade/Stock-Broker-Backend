package com.stockpredict.ranking.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inverse-volatility weighting with position (10%), sector (25%) and
 * minimum-weight caps. Deterministic for a fixed input.
 *
 * <p>Both caps are iterated to convergence. When they are mathematically
 * infeasible for the selected names (e.g. 5 of 10 names share one sector:
 * 0.25 + 5×0.10 = 0.75 &lt; 1), the sector cap is kept strictly and the
 * position cap is relaxed to the smallest feasible spread — the acceptance
 * criterion is "weights sum to 1, no sector above 25%" (journey 3), and the
 * position cap cannot override it.
 */
final class PortfolioWeights {

    private static final int MAX_ITERATIONS = 50;
    private static final double EPSILON = 1e-9;

    private PortfolioWeights() {
    }

    record Candidate(String symbol, double predictedReturn, double volatility, String sector) {
    }

    record Weighted(String symbol, double predictedReturn, double volatility, String sector,
                    double weight) {
    }

    static List<Weighted> inverseVolatility(List<Candidate> candidates,
                                            double maxPositionWeight,
                                            double maxSectorWeight,
                                            double minPositionWeight) {
        int n = candidates.size();
        if (n == 0) {
            return List.of();
        }
        double medianVolatility = median(candidates.stream()
                .map(Candidate::volatility)
                .filter(v -> Double.isFinite(v) && v > 0)
                .toList());

        double[] weights = new double[n];
        for (int i = 0; i < n; i++) {
            double volatility = candidates.get(i).volatility();
            if (!Double.isFinite(volatility) || volatility <= 0) {
                volatility = medianVolatility;
            }
            weights[i] = 1.0 / volatility;
        }
        normalize(weights);

        // Sector cap first: it is the hard acceptance constraint.
        double[] sectorCapped = weights.clone();
        applySectorCap(sectorCapped, candidates, maxSectorWeight);

        double[] combined = sectorCapped.clone();
        boolean feasible = enforceBothCaps(combined, candidates, maxPositionWeight, maxSectorWeight);
        double[] result = feasible ? combined : sectorCapped;

        // Drop dust positions and then re-enforce both caps on the survivors:
        // renormalizing after the drop can otherwise push a remaining name or
        // sector back above its cap (M7). Each pass either drops a name or
        // changes the capped weights, so this terminates.
        boolean[] included = new boolean[n];
        for (int i = 0; i < n; i++) {
            included[i] = result[i] >= minPositionWeight;
        }
        for (int pass = 0; pass < MAX_ITERATIONS; pass++) {
            zeroExcluded(result, included);
            normalize(result);
            boolean capped = enforceCapsOnIncluded(result, candidates, included,
                    maxPositionWeight, maxSectorWeight);
            boolean dropped = false;
            for (int i = 0; i < n; i++) {
                if (included[i] && result[i] < minPositionWeight) {
                    included[i] = false;
                    dropped = true;
                }
            }
            if (!capped && !dropped) {
                break;
            }
        }
        zeroExcluded(result, included);

        double total = 0;
        for (double weight : result) {
            total += weight;
        }
        if (total <= 0) {
            Arrays.fill(result, 1.0 / n);
        } else {
            for (int i = 0; i < n; i++) {
                result[i] /= total;
            }
        }

        List<Weighted> weighted = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Candidate candidate = candidates.get(i);
            if (result[i] > 0) {
                weighted.add(new Weighted(candidate.symbol(), candidate.predictedReturn(),
                        candidate.volatility(), candidate.sector(), result[i]));
            }
        }
        return weighted;
    }

    private static void zeroExcluded(double[] weights, boolean[] included) {
        for (int i = 0; i < weights.length; i++) {
            if (!included[i]) {
                weights[i] = 0;
            }
        }
    }

    /**
     * Re-runs the sector + position capping on the included subset and scatters
     * the result back, so a dust-drop renormalization cannot breach a cap.
     *
     * @return true when any included weight changed
     */
    private static boolean enforceCapsOnIncluded(double[] weights, List<Candidate> candidates,
                                                 boolean[] included, double maxPositionWeight,
                                                 double maxSectorWeight) {
        List<Candidate> subset = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < included.length; i++) {
            if (included[i]) {
                subset.add(candidates.get(i));
                indices.add(i);
            }
        }
        if (subset.isEmpty()) {
            return false;
        }
        double[] subsetWeights = new double[subset.size()];
        for (int i = 0; i < subset.size(); i++) {
            subsetWeights[i] = weights[indices.get(i)];
        }
        double[] sectorCapped = subsetWeights.clone();
        applySectorCap(sectorCapped, subset, maxSectorWeight);
        double[] combined = sectorCapped.clone();
        boolean feasible = enforceBothCaps(combined, subset, maxPositionWeight, maxSectorWeight);
        double[] chosen = feasible ? combined : sectorCapped;
        boolean changed = false;
        for (int i = 0; i < subset.size(); i++) {
            if (Math.abs(weights[indices.get(i)] - chosen[i]) > 1e-12) {
                changed = true;
            }
            weights[indices.get(i)] = chosen[i];
        }
        return changed;
    }

    /**
     * Iterates exact position capping and sector scaling until both hold.
     *
     * @return true when the position cap holds on return, false when the
     *         constraint set is infeasible for this candidate list.
     */
    private static boolean enforceBothCaps(double[] weights, List<Candidate> candidates,
                                           double maxPositionWeight, double maxSectorWeight) {
        if (candidates.size() * maxPositionWeight < 1.0 - EPSILON) {
            return false;
        }
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            capPositions(weights, maxPositionWeight);
            boolean scaled = scaleSectors(weights, candidates, maxSectorWeight);
            normalize(weights);
            if (!scaled) {
                capPositions(weights, maxPositionWeight);
                return allAtMost(weights, maxPositionWeight);
            }
        }
        capPositions(weights, maxPositionWeight);
        return allAtMost(weights, maxPositionWeight);
    }

    /**
     * Water-filling position cap: fix over-cap names at the cap and scale the
     * rest to the remaining budget. Each pass fixes at least one new name, so
     * this converges in at most n passes (no slow asymptotic approach).
     */
    private static void capPositions(double[] weights, double maxPositionWeight) {
        int n = weights.length;
        boolean[] fixed = new boolean[n];
        normalize(weights);
        for (int pass = 0; pass <= n; pass++) {
            double fixedTotal = 0;
            double freeTotal = 0;
            int freeCount = 0;
            for (int i = 0; i < n; i++) {
                if (fixed[i]) {
                    weights[i] = maxPositionWeight;
                    fixedTotal += maxPositionWeight;
                    continue;
                }
                if (weights[i] > maxPositionWeight + 1e-15) {
                    fixed[i] = true;
                    weights[i] = maxPositionWeight;
                    fixedTotal += maxPositionWeight;
                } else {
                    freeTotal += weights[i];
                    freeCount++;
                }
            }
            if (freeCount == 0 || fixedTotal >= 1.0 - EPSILON) {
                if (fixedTotal >= 1.0 - EPSILON) {
                    Arrays.fill(weights, 1.0 / n);
                }
                normalize(weights);
                return;
            }
            double targetFree = 1.0 - fixedTotal;
            for (int i = 0; i < n; i++) {
                if (!fixed[i]) {
                    weights[i] = freeTotal <= 0 ? targetFree / freeCount : weights[i] / freeTotal * targetFree;
                }
            }
        }
    }

    private static void applySectorCap(double[] weights, List<Candidate> candidates,
                                       double maxSectorWeight) {
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            boolean scaled = scaleSectors(weights, candidates, maxSectorWeight);
            normalize(weights);
            if (!scaled) {
                return;
            }
        }
    }

    private static boolean scaleSectors(double[] weights, List<Candidate> candidates,
                                        double maxSectorWeight) {
        Map<String, Double> sectorTotals = new LinkedHashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            sectorTotals.merge(candidates.get(i).sector(), weights[i], Double::sum);
        }
        boolean scaled = false;
        for (int i = 0; i < candidates.size(); i++) {
            double total = sectorTotals.getOrDefault(candidates.get(i).sector(), 0.0);
            if (total > maxSectorWeight + 1e-12) {
                weights[i] *= maxSectorWeight / total;
                scaled = true;
            }
        }
        return scaled;
    }

    private static boolean allAtMost(double[] weights, double limit) {
        for (double weight : weights) {
            if (weight > limit + 1e-6) {
                return false;
            }
        }
        return true;
    }

    private static void normalize(double[] weights) {
        double total = 0;
        for (double weight : weights) {
            total += weight;
        }
        if (total <= 0) {
            Arrays.fill(weights, 1.0 / weights.length);
            return;
        }
        for (int i = 0; i < weights.length; i++) {
            weights[i] /= total;
        }
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) {
            return 1.0;
        }
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2.0;
    }
}
