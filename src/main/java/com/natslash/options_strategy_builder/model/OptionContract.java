package com.natslash.options_strategy_builder.model;

import lombok.Builder;
import lombok.Data;

/**
 * Represents a single OESX option contract with market data.
 * Designed to be serialisable to JSON for a future REST layer.
 */
@Data
@Builder
public class OptionContract {
    // Contract identity
    private String expiry;      // YYYYMMDD
    private int    dte;
    private double strike;
    private String type;        // "C" or "P"

    // Market data
    private Double bid;
    private Double ask;
    private Double last;
    private Double close;       // settlement

    // Greeks (IBKR-native)
    private Double iv;          // as percentage, e.g. 21.5
    private Double delta;
    private Double gamma;
    private Double theta;
    private Double vega;

    // Derived
    private Double undPrice;
    private Double midPrice;
    private Double premiumEur;  // mid * multiplier
    private Double otmPct;      // (strike - spot) / spot * 100

    // Metadata
    private int    bidSize;
    private int    askSize;
    private int    volume;
    private int    openInterest;
    private String greeksSource; // "IBKR" or "NONE"
}
