package com.natslash.options_strategy_builder.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstrumentSearchResult {
    private String  symbol;
    private String  name;
    private String  exchange;
    private String  currency;
    private int     conId;
    private int     multiplier;
    private String  tradingClass;
    private String  secType;
    private boolean alreadySaved;
}
