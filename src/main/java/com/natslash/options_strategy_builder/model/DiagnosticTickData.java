package com.natslash.options_strategy_builder.model;

/**
 * Internal transport for a diagnostic tick fetch — enriches the raw tick with
 * how IBKR actually delivered the data.
 *
 * @param tick            raw market data snapshot
 * @param confirmedMdt    MDT code IBKR confirmed via marketDataType callback
 *                        (1=LIVE, 2=FROZEN, 3=DELAYED, 4=DELAYED_FROZEN, null if never received)
 * @param completionReason how the future resolved: EARLY (Greeks+price arrived within window),
 *                         TIMEOUT (full window elapsed), ERROR_200 (contract not found),
 *                         ERROR_XXX (other IBKR error code)
 */
public record DiagnosticTickData(
        TickData tick,
        Integer confirmedMdt,
        String completionReason) {}
