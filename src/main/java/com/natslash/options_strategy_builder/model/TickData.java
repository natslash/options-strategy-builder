package com.natslash.options_strategy_builder.model;

/** Snapshot of market data ticks received from IBKR for a single contract. */
public record TickData(
        Double bid, Double ask, Double last, Double close,
        Double optPrice, Double undPrice,
        Double impliedVol, Double delta, Double gamma, Double vega, Double theta,
        int bidSize, int askSize, int volume, int openInterest, boolean greeksReceived) {

    /** Returned when IBGW is not connected — all fields null/zero/false. */
    public static final TickData EMPTY = new TickData(
            null, null, null, null, null, null,
            null, null, null, null, null,
            0, 0, 0, 0, false);

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
