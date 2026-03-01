package com.natslash.options_strategy_builder.model;

import java.util.List;
import java.util.Map;

/**
 * Chain-level sentiment metrics derived from open interest data.
 *
 * @param maxPain   strike at which aggregate writer pain is minimised
 * @param pcr       put-call ratio (putOI / callOI)
 * @param oiWalls   strikes where OI exceeds 2× the average OI across all strikes
 * @param pcByExpiry put-call ratio broken down by expiry date
 */
public record ChainAnalysisResult(
        double maxPain,
        double pcr,
        List<Double> oiWalls,
        Map<String, Double> pcByExpiry) {}
