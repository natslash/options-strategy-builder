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
     *
     * @param low             lower bound (e.g. breakEvenLow)
     * @param high            upper bound (e.g. breakEvenHigh)
     * @param underlyingPrice mean of the distribution
     * @param expectedMove    standard deviation (from {@link #expectedMove})
     * @return probability in [0, 1]
     */
    public double probabilityBetween(double low, double high, double underlyingPrice, double expectedMove) {
        if (expectedMove <= 0) return 0.0;
        NormalDistribution dist = new NormalDistribution(underlyingPrice, expectedMove);
        return dist.cumulativeProbability(high) - dist.cumulativeProbability(low);
    }
}
