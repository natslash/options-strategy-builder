package com.natslash.options_strategy_builder.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StrategyRequest {
    private String            name;
    private double            spot;
    private Long              instrumentId;
    private List<StrategyLeg> legs;
}
