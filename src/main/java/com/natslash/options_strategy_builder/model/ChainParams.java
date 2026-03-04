package com.natslash.options_strategy_builder.model;

import java.util.List;
import java.util.Optional;

/**
 * Option chain parameters returned by IBKR, grouped by tradingClass to preserve
 * the correct strike grid for each expiry series.
 *
 * <p>IBKR fires one {@code securityDefinitionOptionalParameter} callback per tradingClass
 * (e.g. monthly OESX at 25pt, weekly OESXW at finer intervals). Merging them produces a
 * theoretical superset that includes strikes with no active contract, causing error 200
 * on every {@code reqMktData} for the non-existent strikes.
 */
public record ChainParams(List<TradingClassParams> tradingClasses) {

    /**
     * Finds the tradingClass entry whose expiration set contains the given expiry date.
     * Used to resolve the correct strike grid and tradingClass label per expiry when
     * building option contracts for {@code reqMktData}.
     */
    public Optional<TradingClassParams> forExpiry(String expiry) {
        return tradingClasses.stream()
                .filter(tc -> tc.expirations().contains(expiry))
                .findFirst();
    }

    /** All expirations across all tradingClasses, sorted ascending. */
    public List<String> allExpirations() {
        return tradingClasses.stream()
                .flatMap(tc -> tc.expirations().stream())
                .distinct().sorted().toList();
    }

    /**
     * All strikes across all tradingClasses, sorted ascending.
     * Used only for display-range statistics — do NOT use for contract building.
     */
    public List<Double> allStrikes() {
        return tradingClasses.stream()
                .flatMap(tc -> tc.strikes().stream())
                .distinct().sorted().toList();
    }
}
