package com.stockpredict.marketdata.guard;

import com.stockpredict.marketdata.api.model.Bar;
import java.util.List;

/**
 * Outcome of a quality-guard pass. {@code flagged} bars are also part of
 * {@code accepted}: they passed the hard checks but tripped a soft threshold.
 */
public record GuardResult(List<Bar> accepted, List<Bar> rejected, List<Bar> flagged) {

    public GuardResult {
        accepted = List.copyOf(accepted);
        rejected = List.copyOf(rejected);
        flagged = List.copyOf(flagged);
    }

    public int acceptedCount() {
        return accepted.size();
    }

    public int rejectedCount() {
        return rejected.size();
    }

    public int flaggedCount() {
        return flagged.size();
    }

    public boolean hasRejections() {
        return !rejected.isEmpty();
    }
}
