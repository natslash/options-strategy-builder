package com.natslash.options_strategy_builder.model;

import java.util.List;

/** Option chain parameters returned by IBKR for a given underlying. */
public record ChainParams(List<String> expirations, List<Double> strikes) {}
