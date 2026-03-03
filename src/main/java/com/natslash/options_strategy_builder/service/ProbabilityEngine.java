package com.natslash.options_strategy_builder.service;

import org.apache.commons.math3.distribution.NormalDistribution;
import org.springframework.stereotype.Service;

/**
 * Pure stateless probability calculator using Black-Scholes lognormal assumptions.
 * Uses Apache Commons Math 3 NormalDistribution for CDF lookups.
 */
@Service
public class ProbabilityEngine {

    /**
     * 1-standard-deviation expected move (in points) at expiry.
     *
     * @param underlyingPrice futures or spot price
     * @param iv              implied volatility as a fraction (e.g. 0.20 for 20%)
     * @param dte             calendar days to expiry
     */
    public double expectedMove(double underlyingPrice, double iv, int dte) {
        return underlyingPrice * iv * Math.sqrt(dte / 365.0);
    }

    /**
     * Probability that the underlying settles between [low, high] at expiry,
     * using a normal distribution centred at underlyingPrice with stdev=expectedMove.
     */
    public double probabilityBetween(double low, double high, double underlyingPrice, double expectedMove) {
        if (expectedMove <= 0) return 0.0;
        NormalDistribution dist = new NormalDistribution(underlyingPrice, expectedMove);
        return dist.cumulativeProbability(high) - dist.cumulativeProbability(low);
    }

    /**
     * Probability of profit for any strategy shape, determined by its break-even points
     * and the profit polarity at the low end of the price range.
     *
     * <table>
     *   <tr><th>Break-evens</th><th>profitAtLeft</th><th>Pattern</th><th>PoP</th></tr>
     *   <tr><td>0</td><td>true</td><td>credit spread — always profitable</td><td>1.0</td></tr>
     *   <tr><td>0</td><td>false</td><td>always-loss</td><td>0.0</td></tr>
     *   <tr><td>1</td><td>true</td><td>long put / bear debit spread</td><td>CDF(be)</td></tr>
     *   <tr><td>1</td><td>false</td><td>long call / bull debit spread</td><td>1 - CDF(be)</td></tr>
     *   <tr><td>2</td><td>false</td><td>short straddle / iron condor</td><td>CDF(beH) - CDF(beL)</td></tr>
     *   <tr><td>2</td><td>true</td><td>long straddle / strangle</td><td>CDF(beL) + (1 - CDF(beH))</td></tr>
     * </table>
     *
     * @param breakEvenLow   lower break-even, or null if none
     * @param breakEvenHigh  upper break-even, or null if none (ignored when breakEvenLow is null)
     * @param profitAtLeft   true if the strategy is profitable at the leftmost (lowest) spot price
     * @param underlyingPrice mean of the lognormal distribution
     * @param expectedMove   standard deviation (from {@link #expectedMove})
     * @return probability in [0, 1]
     */
    public double computePop(Double breakEvenLow, Double breakEvenHigh,
                             boolean profitAtLeft,
                             double underlyingPrice, double expectedMove) {
        if (expectedMove <= 0) return 0.0;

        if (breakEvenLow == null) {
            return profitAtLeft ? 1.0 : 0.0;
        }

        NormalDistribution dist = new NormalDistribution(underlyingPrice, expectedMove);

        if (breakEvenHigh == null) {
            // One break-even: profitable on one side
            return profitAtLeft
                    ? dist.cumulativeProbability(breakEvenLow)         // profit below be
                    : 1.0 - dist.cumulativeProbability(breakEvenLow);  // profit above be
        }

        // Two break-evens: profitable either inside or outside
        return profitAtLeft
                // Profit outside break-evens (long straddle / strangle)
                ? dist.cumulativeProbability(breakEvenLow) + (1.0 - dist.cumulativeProbability(breakEvenHigh))
                // Profit inside break-evens (short straddle / iron condor)
                : dist.cumulativeProbability(breakEvenHigh) - dist.cumulativeProbability(breakEvenLow);
    }
}
