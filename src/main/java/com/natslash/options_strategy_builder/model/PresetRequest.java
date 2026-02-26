package com.natslash.options_strategy_builder.model;

import lombok.Data;

@Data
public class PresetRequest {
    private String preset;      // e.g. "IRON_CONDOR"
    private Long   instrumentId;
    private double spot;
    private String expiry;
    private int    dte;
    private double deltaTarget; // e.g. 0.20 for 20-delta wings
    private String name;
}
