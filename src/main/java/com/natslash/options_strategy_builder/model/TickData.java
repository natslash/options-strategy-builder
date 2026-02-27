package com.natslash.options_strategy_builder.model;

/** Snapshot of market data ticks received from IBKR for a single contract. */
public record TickData(
        Double bid, Double ask, Double last, Double close,
        Double optPrice, Double undPrice,
        Double impliedVol, Double delta, Double gamma, Double vega, Double theta,
        int volume, int openInterest, boolean greeksReceived) {

    public boolean hasData() {
        return bid != null || ask != null || last != null || close != null || greeksReceived;
    }

    public Double mid() {
        if (bid != null && ask != null) return (bid + ask) / 2.0;
        if (bid != null)  return bid;
        if (ask != null)  return ask;
        return last;
    }
}
