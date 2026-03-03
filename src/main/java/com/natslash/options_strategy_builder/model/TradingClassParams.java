package com.natslash.options_strategy_builder.model;

import java.util.List;

/**
 * Option chain parameters for a single tradingClass returned by IBKR
 * (e.g. "OESX" monthly at 25pt intervals, "OESXW" weekly at finer intervals).
 * Keeping tradingClasses separate avoids the superset-strike problem where
 * merging all grids produces theoretical strikes that have no active contract.
 */
public record TradingClassParams(
        String tradingClass,
        List<String> expirations,
        List<Double> strikes) {}
