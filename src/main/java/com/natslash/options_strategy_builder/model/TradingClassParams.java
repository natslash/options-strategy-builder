package com.natslash.options_strategy_builder.model;

import java.util.List;

/**
 * Option chain parameters for a single tradingClass returned by IBKR
 * (e.g. "OESX" monthly at 25pt intervals, "OESXW" weekly at finer intervals).
 * Keeping tradingClasses separate avoids the superset-strike problem where
 * merging all grids produces theoretical strikes that have no active contract.
 *
 * <p>{@code optionsExchange} is the actual exchange returned in the
 * {@code securityDefinitionOptionalParameter} callback (e.g. "EUREX" for BAYN even
 * though the underlying instrument is routed via "SMART"). Always use this when
 * building OPT contracts — never use the instrument's underlying exchange for options.
 */
public record TradingClassParams(
        String tradingClass,
        String optionsExchange,
        List<String> expirations,
        List<Double> strikes) {}
