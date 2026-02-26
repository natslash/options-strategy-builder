package com.natslash.options_strategy_builder.model;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class StrategyAnalysis {
    private String name;
    private double spot;

    // Net Greeks across all legs
    private double netDelta;
    private double netGamma;
    private double netTheta;
    private double netVega;
    private double netPremium;  // net credit (+) or debit (-)

    // P&L at expiry for spot range
    private Map<Integer, Double> pnlAtExpiry;  // spot → P&L in EUR

    // Risk metrics
    private double maxProfit;
    private double maxLoss;
    private Double breakEvenLow;
    private Double breakEvenHigh;

    private List<StrategyLeg> legs;
}