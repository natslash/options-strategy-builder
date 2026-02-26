package com.natslash.options_strategy_builder.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PresetStrategy {
    SHORT_PUT         ("Short Put",          "Neutral/Bullish",  "Sell OTM put. Profit if market stays above strike."),
    SHORT_STRANGLE    ("Short Strangle",     "Neutral",          "Sell OTM call + OTM put. Profit in range-bound market."),
    SHORT_STRADDLE    ("Short Straddle",     "Neutral",          "Sell ATM call + put. Max premium, unlimited risk."),
    IRON_CONDOR       ("Iron Condor",        "Neutral",          "Short strangle + long wings. Defined risk/reward."),
    IRON_BUTTERFLY    ("Iron Butterfly",     "Neutral",          "Short straddle + long wings. Tighter but higher premium."),
    BEAR_PUT_SPREAD   ("Bear Put Spread",    "Bearish",          "Buy higher put, sell lower put. Debit, capped profit."),
    SHORT_CALL_SPREAD ("Short Call Spread",  "Bearish",          "Sell lower call, buy higher call. Credit, capped risk."),
    BULL_PUT_SPREAD   ("Bull Put Spread",    "Bullish",          "Sell higher put, buy lower put. Credit, defined risk."),
    LONG_STRANGLE     ("Long Strangle",      "Volatile",         "Buy OTM call + put. Profit on big moves either way."),
    LONG_STRADDLE     ("Long Straddle",      "Volatile",         "Buy ATM call + put. Maximum volatility play.");

    private final String displayName;
    private final String outlook;
    private final String description;
}