package com.natslash.options_strategy_builder.model;

import java.util.List;

/** Lightweight metadata returned by /api/chain/params — no contract data, no IBKR tick requests. */
public record ChainFilterParams(
        double       spot,
        List<String> expiries,      // all available expiries, sorted (YYYYMMDD)
        double       strikeMin,
        double       strikeMax,
        int          strikeCount,
        List<Double> windowStrikes  // index-based ±N/2 window around ATM
) {}
