package com.natslash.options_strategy_builder.service;

import com.natslash.options_strategy_builder.model.StrategyLeg;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Pure stateless liquidity scorer for a set of strategy legs.
 *
 * <p>Score per leg (0–1):
 * <ul>
 *   <li>spreadPenalty: (ask - bid) / mid → 1.0 if &lt;2%, 0.5 if 2–10%, 0.0 if &gt;10%</li>
 *   <li>oiScore: OI &gt;100 → 1.0, OI &gt;10 → 0.5, else 0.0</li>
 *   <li>legScore = (spreadPenalty + oiScore) / 2</li>
 * </ul>
 * Returns null if no leg has bid/ask/OI data.
 */
@Service
public class LiquidityScorer {

    /**
     * Scores the liquidity of the given legs.
     *
     * @return overall score in [0, 1], or null if no bid/ask/OI data is present on any leg
     */
    public Double score(List<StrategyLeg> legs) {
        if (legs == null || legs.isEmpty()) return null;

        double totalScore = 0;
        int scoredLegs = 0;

        for (StrategyLeg leg : legs) {
            Double bid = leg.getBid();
            Double ask = leg.getAsk();
            Integer oi = leg.getOpenInterest();

            // Only score legs where we have at least some data
            if (bid == null && ask == null && oi == null) continue;

            double spreadPenalty = spreadPenalty(bid, ask);
            double oiScore = oiScore(oi);
            totalScore += (spreadPenalty + oiScore) / 2.0;
            scoredLegs++;
        }

        if (scoredLegs == 0) return null;
        return totalScore / scoredLegs;
    }

    private double spreadPenalty(Double bid, Double ask) {
        if (bid == null || ask == null || bid <= 0 || ask <= 0) return 0.0;
        double mid = (bid + ask) / 2.0;
        if (mid <= 0) return 0.0;
        double spreadPct = (ask - bid) / mid;
        if (spreadPct < 0.02) return 1.0;
        if (spreadPct <= 0.10) return 0.5;
        return 0.0;
    }

    private double oiScore(Integer oi) {
        if (oi == null) return 0.0;
        if (oi > 100) return 1.0;
        if (oi > 10)  return 0.5;
        return 0.0;
    }
}
