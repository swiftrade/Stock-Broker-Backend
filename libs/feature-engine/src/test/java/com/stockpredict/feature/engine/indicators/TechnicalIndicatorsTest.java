package com.stockpredict.feature.engine.indicators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class TechnicalIndicatorsTest {

    @Test
    void logReturnsMatchManualComputation() {
        double[] close = {100.0, 110.0, 99.0};
        double[] out = TechnicalIndicators.logReturns(close, 1);
        assertThat(out[0]).isNaN();
        assertThat(out[1]).isCloseTo(Math.log(1.1), within(1e-12));
        assertThat(out[2]).isCloseTo(Math.log(99.0 / 110.0), within(1e-12));
    }

    @Test
    void pctChangeMatchesPandas() {
        double[] out = TechnicalIndicators.pctChange(new double[] {100, 110, 99});
        assertThat(out[0]).isNaN();
        assertThat(out[1]).isCloseTo(0.1, within(1e-12));
        assertThat(out[2]).isCloseTo(-0.1, within(1e-12));
    }

    @Test
    void rollingMeanAndStdMatchPandas() {
        double[] values = {1, 2, 3, 4};
        double[] mean = TechnicalIndicators.rollingMean(values, 2);
        assertThat(mean[0]).isNaN();
        assertThat(mean[1]).isCloseTo(1.5, within(1e-12));
        assertThat(mean[3]).isCloseTo(3.5, within(1e-12));

        double[] std = TechnicalIndicators.rollingStd(values, 2);
        assertThat(std[0]).isNaN();
        // pandas sample std of [1,2] is sqrt(0.5) = 0.7071...
        assertThat(std[1]).isCloseTo(Math.sqrt(0.5), within(1e-12));
    }

    @Test
    void movingAverageRatio() {
        double[] ratio = TechnicalIndicators.movingAverageRatio(new double[] {10, 20, 30}, 2);
        assertThat(ratio[0]).isNaN();
        assertThat(ratio[1]).isCloseTo(20.0 / 15.0, within(1e-12));
        assertThat(ratio[2]).isCloseTo(30.0 / 25.0, within(1e-12));
    }

    @Test
    void emaMatchesAdjustFalse() {
        double[] out = TechnicalIndicators.ema(new double[] {1, 2, 3, 4}, 3);
        assertThat(out[0]).isCloseTo(1.0, within(1e-12));
        assertThat(out[1]).isCloseTo(1.5, within(1e-12));
        assertThat(out[2]).isCloseTo(2.25, within(1e-12));
        assertThat(out[3]).isCloseTo(3.125, within(1e-12));
    }

    @Test
    void rsiIsNaNForMonotonicRiseAnd100ForMixedUpTrend() {
        // All gains => avgLoss == 0 => Python replaces 0 with NaN => RSI NaN.
        double[] allUp = TechnicalIndicators.wildersRsi(new double[] {1, 2, 3, 4}, 2);
        assertThat(allUp[3]).isNaN();

        // A single loss produces a finite RSI below 100.
        double[] mixed = TechnicalIndicators.wildersRsi(new double[] {1, 2, 3, 2.9, 3.0}, 2);
        assertThat(mixed[4]).isFinite().isBetween(0.0, 100.0);
    }

    @Test
    void macdLineIsFastMinusSlowEma() {
        double[] close = {10, 11, 12, 13, 14, 15, 14, 13, 14, 16, 18, 20, 22, 21, 23, 25, 24, 26, 28, 30};
        TechnicalIndicators.Macd macd = TechnicalIndicators.macd(close);
        double[] fast = TechnicalIndicators.ema(close, 12);
        double[] slow = TechnicalIndicators.ema(close, 26);
        for (int i = 0; i < close.length; i++) {
            assertThat(macd.line()[i]).isCloseTo(fast[i] - slow[i], within(1e-12));
        }
    }

    @Test
    void bollingerHandlesConstantSeries() {
        double[] close = {5, 5, 5, 5};
        TechnicalIndicators.Bollinger bb = TechnicalIndicators.bollinger(close, 2, 2);
        assertThat(bb.upper()[1]).isCloseTo(5.0, within(1e-12));
        assertThat(bb.lower()[1]).isCloseTo(5.0, within(1e-12));
        assertThat(bb.width()[1]).isCloseTo(0.0, within(1e-12));
        assertThat(bb.position()[1]).isNaN();
    }

    @Test
    void atrUsesTrueRange() {
        double[] high = {10, 12, 13};
        double[] low = {8, 9, 11};
        double[] close = {9, 11, 12};
        double[] atr = TechnicalIndicators.atr(high, low, close, 2);
        assertThat(atr[0]).isNaN();
        // TR[0]=2, TR[1]=max(3, |12-9|, |9-9|)=3 -> mean 2.5
        assertThat(atr[1]).isCloseTo(2.5, within(1e-12));
    }

    @Test
    void crossSectionalRankMatchesPandasPct() {
        double[] ranks = TechnicalIndicators.crossSectionalRank(new double[] {10, 20, 30});
        assertThat(ranks[0]).isCloseTo(1.0 / 3.0, within(1e-12));
        assertThat(ranks[1]).isCloseTo(2.0 / 3.0, within(1e-12));
        assertThat(ranks[2]).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void crossSectionalRankAveragesTies() {
        double[] ranks = TechnicalIndicators.crossSectionalRank(new double[] {1, 2, 2, 3});
        // Tie at positions 1 and 2 -> average rank (2+3)/2 = 2.5 over 4 = 0.625
        assertThat(ranks[1]).isCloseTo(0.625, within(1e-12));
        assertThat(ranks[2]).isCloseTo(0.625, within(1e-12));
    }
}
