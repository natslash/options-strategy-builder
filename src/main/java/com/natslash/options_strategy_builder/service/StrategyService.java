package com.natslash.options_strategy_builder.service;

import com.natslash.options_strategy_builder.entity.Instrument;
import com.natslash.options_strategy_builder.model.*;
import com.natslash.options_strategy_builder.repository.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyService {

    private final MarketDataService    marketDataService;
    private final InstrumentRepository instrumentRepository;

    // ═══════════════════════════════════════════════════════════
    // Analyze
    // ═══════════════════════════════════════════════════════════

    public StrategyAnalysis analyze(StrategyRequest req) {
        List<StrategyLeg> legs       = req.getLegs();
        double            spot       = req.getSpot();
        int               multiplier = resolveMultiplier(req);

        // Net Greeks
        double netDelta = 0, netGamma = 0, netTheta = 0, netVega = 0, netPremium = 0;
        for (StrategyLeg leg : legs) {
            double sign = "LONG".equals(leg.getDirection()) ? 1 : -1;
            int    qty  = leg.getQuantity();
            if (leg.getDelta()   != null) netDelta   += sign * qty * leg.getDelta();
            if (leg.getGamma()   != null) netGamma   += sign * qty * leg.getGamma();
            if (leg.getTheta()   != null) netTheta   += sign * qty * leg.getTheta();
            if (leg.getVega()    != null) netVega    += sign * qty * leg.getVega();
            if (leg.getPremium() != null) netPremium -= sign * qty * leg.getPremium() * multiplier;
        }

        // P&L at expiry across spot range
        int step  = 25;
        int range = 1000;
        Map<Integer, Double> pnlAtExpiry = new TreeMap<>();
        for (int s = (int) spot - range; s <= (int) spot + range; s += step) {
            double pnl = 0;
            for (StrategyLeg leg : legs) {
                double sign      = "LONG".equals(leg.getDirection()) ? 1 : -1;
                double intrinsic = intrinsicValue(leg, s);
                double entry     = leg.getPremium() != null ? leg.getPremium() : 0;
                pnl += sign * leg.getQuantity() * (intrinsic - entry) * multiplier;
            }
            pnlAtExpiry.put(s, Math.round(pnl * 100.0) / 100.0);
        }

        double maxProfit = pnlAtExpiry.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double maxLoss   = pnlAtExpiry.values().stream().mapToDouble(Double::doubleValue).min().orElse(0);

        // Break-even points — where P&L crosses zero
        Double        breakEvenLow = null, breakEvenHigh = null;
        List<Integer> spots        = new ArrayList<>(pnlAtExpiry.keySet());
        for (int i = 1; i < spots.size(); i++) {
            double prev = pnlAtExpiry.get(spots.get(i - 1));
            double curr = pnlAtExpiry.get(spots.get(i));
            if (prev * curr <= 0) {
                double be = spots.get(i - 1) + (0 - prev) / (curr - prev) * step;
                if (breakEvenLow == null) breakEvenLow = be;
                else                      breakEvenHigh = be;
            }
        }

        return StrategyAnalysis.builder()
                .name(req.getName())
                .spot(spot)
                .netDelta(round(netDelta))
                .netGamma(round(netGamma))
                .netTheta(round(netTheta))
                .netVega(round(netVega))
                .netPremium(round(netPremium))
                .pnlAtExpiry(pnlAtExpiry)
                .maxProfit(maxProfit)
                .maxLoss(maxLoss)
                .breakEvenLow(breakEvenLow)
                .breakEvenHigh(breakEvenHigh)
                .legs(legs)
                .build();
    }

    // ═══════════════════════════════════════════════════════════
    // Build preset
    // ═══════════════════════════════════════════════════════════

    public StrategyRequest buildPreset(PresetRequest req, double spot) {
        double deltaTarget = req.getDeltaTarget() > 0 ? req.getDeltaTarget() : 0.20;
        String expiry      = req.getExpiry();

        // ATM strike
        int atm = (int) (Math.round(spot / 25.0) * 25);

        // Approximate OTM strike from delta target
        int otmOffset  = (int) (Math.round((0.50 - deltaTarget) / 0.05) * 25);
        int wingOffset = otmOffset + 100;

        int otmCall  = atm + otmOffset;
        int otmPut   = atm - otmOffset;
        int wingCall = atm + wingOffset;
        int wingPut  = atm - wingOffset;

        List<StrategyLeg> legs = new ArrayList<>();

        switch (PresetStrategy.valueOf(req.getPreset())) {
            case SHORT_PUT      -> legs.add(leg(expiry, otmPut,  "P", "SHORT", null));
            case SHORT_STRANGLE -> {
                legs.add(leg(expiry, otmCall, "C", "SHORT", null));
                legs.add(leg(expiry, otmPut,  "P", "SHORT", null));
            }
            case SHORT_STRADDLE -> {
                legs.add(leg(expiry, atm, "C", "SHORT", null));
                legs.add(leg(expiry, atm, "P", "SHORT", null));
            }
            case IRON_CONDOR -> {
                legs.add(leg(expiry, wingCall, "C", "LONG",  null));
                legs.add(leg(expiry, otmCall,  "C", "SHORT", null));
                legs.add(leg(expiry, otmPut,   "P", "SHORT", null));
                legs.add(leg(expiry, wingPut,  "P", "LONG",  null));
            }
            case IRON_BUTTERFLY -> {
                legs.add(leg(expiry, wingCall, "C", "LONG",  null));
                legs.add(leg(expiry, atm,      "C", "SHORT", null));
                legs.add(leg(expiry, atm,      "P", "SHORT", null));
                legs.add(leg(expiry, wingPut,  "P", "LONG",  null));
            }
            case BEAR_PUT_SPREAD -> {
                legs.add(leg(expiry, atm,    "P", "LONG",  null));
                legs.add(leg(expiry, otmPut, "P", "SHORT", null));
            }
            case SHORT_CALL_SPREAD -> {
                legs.add(leg(expiry, atm,     "C", "SHORT", null));
                legs.add(leg(expiry, otmCall, "C", "LONG",  null));
            }
            case BULL_PUT_SPREAD -> {
                legs.add(leg(expiry, atm,    "P", "SHORT", null));
                legs.add(leg(expiry, otmPut, "P", "LONG",  null));
            }
            case LONG_STRANGLE -> {
                legs.add(leg(expiry, otmCall, "C", "LONG", null));
                legs.add(leg(expiry, otmPut,  "P", "LONG", null));
            }
            case LONG_STRADDLE -> {
                legs.add(leg(expiry, atm, "C", "LONG", null));
                legs.add(leg(expiry, atm, "P", "LONG", null));
            }
        }

        // Snap leg strikes to nearest available strike from IBKR chain
        enrichLegsFromChain(legs, req.getInstrumentId());

        return new StrategyRequest(
                req.getName() != null ? req.getName() : PresetStrategy.valueOf(req.getPreset()).getDisplayName(),
                spot,
                req.getInstrumentId(),
                legs);
    }

    // ═══════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════

    /**
     * Snaps leg strikes to the nearest available strike returned by MarketDataService.
     * No-ops silently when instrumentId is null or strikes are unavailable.
     */
    private void enrichLegsFromChain(List<StrategyLeg> legs, Long instrumentId) {
        if (instrumentId == null) return;
        List<Double> strikes = marketDataService.getAvailableStrikes(instrumentId);
        if (strikes.isEmpty()) return;
        for (StrategyLeg leg : legs) {
            double nearest = strikes.stream()
                    .min(Comparator.comparingDouble(s -> Math.abs(s - leg.getStrike())))
                    .orElse(leg.getStrike());
            leg.setStrike(nearest);
        }
    }

    /**
     * Resolves the contract multiplier from the instrument DB record.
     * Falls back to 10 (ESTX50 default) when no instrumentId is provided.
     */
    private int resolveMultiplier(StrategyRequest req) {
        if (req.getInstrumentId() != null) {
            return instrumentRepository.findById(req.getInstrumentId())
                    .map(Instrument::getMultiplier)
                    .orElse(10);
        }
        return 10;
    }

    private double intrinsicValue(StrategyLeg leg, double spot) {
        return "C".equals(leg.getType())
                ? Math.max(0, spot - leg.getStrike())
                : Math.max(0, leg.getStrike() - spot);
    }

    private double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private StrategyLeg leg(String expiry, double strike, String type,
                             String direction, Double premium) {
        StrategyLeg leg = new StrategyLeg();
        leg.setExpiry(expiry);
        leg.setStrike(strike);
        leg.setType(type);
        leg.setDirection(direction);
        leg.setQuantity(1);
        leg.setPremium(premium);
        return leg;
    }
}
