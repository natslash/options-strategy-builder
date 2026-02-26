package com.natslash.options_strategy_builder.model;

import lombok.Data;

@Data
public class StrategyLeg {
    private String expiry;
    private double strike;
    private String type;        // "C" or "P"
    private String direction;   // "LONG" or "SHORT"
    private int    quantity;
    private Double premium;     // mid price at time of analysis
    private Double delta;
    private Double gamma;
    private Double theta;
    private Double vega;
    private Double iv;
}