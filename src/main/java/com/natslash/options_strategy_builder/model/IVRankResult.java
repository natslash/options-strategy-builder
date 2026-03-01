package com.natslash.options_strategy_builder.model;

/**
 * IV Rank result for an instrument.
 *
 * @param currentIv  current implied volatility (as fraction, e.g. 0.20 for 20%)
 * @param ivRank     percentile rank in [0, 1] over the past 52 weeks
 * @param hvRatio    currentIv / hv30 (>1.0 means options are expensive; selling has edge)
 * @param label      "LOW" (<25%), "MODERATE" (25–50%), "ELEVATED" (50–75%), "HIGH" (≥75%)
 */
public record IVRankResult(double currentIv, double ivRank, double hvRatio, String label) {

    public static String labelFor(double ivRank) {
        if (ivRank < 0.25) return "LOW";
        if (ivRank < 0.50) return "MODERATE";
        if (ivRank < 0.75) return "ELEVATED";
        return "HIGH";
    }
}
