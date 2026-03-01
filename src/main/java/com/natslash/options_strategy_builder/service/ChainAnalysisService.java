package com.natslash.options_strategy_builder.service;

import com.natslash.options_strategy_builder.model.ChainAnalysisResult;
import com.natslash.options_strategy_builder.model.OptionContract;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes chain-level sentiment metrics from open interest data.
 * Pure stateless — no IBKR calls; input is a pre-fetched chain.
 */
@Service
public class ChainAnalysisService {

    /**
     * Analyses the provided chain and returns max pain, PCR, OI walls, and per-expiry PCR.
     */
    public ChainAnalysisResult analyze(List<OptionContract> chain) {
        List<Double> strikes = chain.stream()
                .map(OptionContract::getStrike)
                .distinct()
                .sorted()
                .toList();

        double maxPain = maxPain(chain, strikes);
        double pcr     = putCallRatio(chain);
        List<Double> oiWalls = oiWalls(chain, strikes);
        Map<String, Double> pcByExpiry = pcrByExpiry(chain);

        return new ChainAnalysisResult(maxPain, pcr, oiWalls, pcByExpiry);
    }

    // ── Internal calculations ──────────────────────────────────

    double maxPain(List<OptionContract> chain, List<Double> strikes) {
        double minPain = Double.MAX_VALUE;
        double maxPainStrike = strikes.isEmpty() ? 0.0 : strikes.get(0);

        for (double candidate : strikes) {
            double totalPain = 0;
            for (OptionContract c : chain) {
                int oi = c.getOpenInterest();
                if (oi <= 0) continue;
                if ("C".equals(c.getType())) {
                    // Call writer pain: max(0, S - strike) * OI
                    totalPain += Math.max(0, candidate - c.getStrike()) * oi;
                } else {
                    // Put writer pain: max(0, strike - S) * OI
                    totalPain += Math.max(0, c.getStrike() - candidate) * oi;
                }
            }
            if (totalPain < minPain) {
                minPain = totalPain;
                maxPainStrike = candidate;
            }
        }
        return maxPainStrike;
    }

    double putCallRatio(List<OptionContract> chain) {
        long putOI  = chain.stream().filter(c -> "P".equals(c.getType())).mapToLong(OptionContract::getOpenInterest).sum();
        long callOI = chain.stream().filter(c -> "C".equals(c.getType())).mapToLong(OptionContract::getOpenInterest).sum();
        return (callOI == 0) ? 0.0 : (double) putOI / callOI;
    }

    List<Double> oiWalls(List<OptionContract> chain, List<Double> strikes) {
        double avgOI = chain.stream().mapToInt(OptionContract::getOpenInterest).average().orElse(0);
        if (avgOI == 0) return List.of();

        // Group by strike and sum OI across puts + calls
        Map<Double, Long> oiByStrike = chain.stream()
                .collect(Collectors.groupingBy(OptionContract::getStrike,
                        Collectors.summingLong(OptionContract::getOpenInterest)));

        return strikes.stream()
                .filter(s -> oiByStrike.getOrDefault(s, 0L) > 2 * avgOI)
                .toList();
    }

    Map<String, Double> pcrByExpiry(List<OptionContract> chain) {
        Map<String, Long> putOiByExpiry = chain.stream()
                .filter(c -> "P".equals(c.getType()))
                .collect(Collectors.groupingBy(OptionContract::getExpiry,
                        Collectors.summingLong(OptionContract::getOpenInterest)));

        Map<String, Long> callOiByExpiry = chain.stream()
                .filter(c -> "C".equals(c.getType()))
                .collect(Collectors.groupingBy(OptionContract::getExpiry,
                        Collectors.summingLong(OptionContract::getOpenInterest)));

        Set<String> expiries = new TreeSet<>();
        expiries.addAll(putOiByExpiry.keySet());
        expiries.addAll(callOiByExpiry.keySet());

        Map<String, Double> result = new LinkedHashMap<>();
        for (String expiry : expiries) {
            long callOI = callOiByExpiry.getOrDefault(expiry, 0L);
            long putOI  = putOiByExpiry.getOrDefault(expiry, 0L);
            result.put(expiry, callOI == 0 ? 0.0 : (double) putOI / callOI);
        }
        return result;
    }
}
