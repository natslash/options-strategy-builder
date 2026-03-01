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

    // Futures & basis
    private Double futuresPrice;   // null if instrument has no futuresConId
    private Double basisPct;       // (futures - spot) / spot * 100

    // Volatility context
    private Double ivRank;         // 0–1, null if IBKR unavailable
    private String ivRankLabel;    // "LOW" / "MODERATE" / "ELEVATED" / "HIGH"
    private Double hvRatio;        // IV/HV30 (>1.0 = selling edge)

    // Probability & expected move (uses futuresPrice if available, else spot)
    private Double expectedMoveUp;   // underlying + 1SD
    private Double expectedMoveDown; // underlying - 1SD
    private Double pop;              // 0–1 probability both break-evens hold
    private boolean breakEvensSafe;  // true if both BEs are outside the 1-SD range

    // Liquidity
    private Double liquidityScore;   // 0–1, null if no bid/ask/OI in legs

    // Risk flags
    private boolean gammaRisk;   // abs(netGamma) > 0.005 AND minDte <= 5
    private Integer minDte;      // minimum DTE across all legs
}
