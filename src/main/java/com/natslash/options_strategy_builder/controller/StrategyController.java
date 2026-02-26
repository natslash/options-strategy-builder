package com.natslash.options_strategy_builder.controller;

import com.natslash.options_strategy_builder.model.PresetRequest;
import com.natslash.options_strategy_builder.model.PresetStrategy;
import com.natslash.options_strategy_builder.model.StrategyAnalysis;
import com.natslash.options_strategy_builder.model.StrategyRequest;
import com.natslash.options_strategy_builder.service.StrategyService;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/strategy")
@RequiredArgsConstructor
public class StrategyController {

    private final StrategyService strategyService;

    @PostMapping("/analyze")
    public StrategyAnalysis analyze(@RequestBody StrategyRequest request) {
        return strategyService.analyze(request);
    }

    @GetMapping("/presets")
public List<Map<String, String>> getPresets() {
    return Arrays.stream(PresetStrategy.values())
        .map(p -> Map.of(
            "key",         p.name(),
            "name",        p.getDisplayName(),
            "outlook",     p.getOutlook(),
            "description", p.getDescription()
        ))
        .toList();
}

@PostMapping("/preset/build")
public StrategyRequest buildPreset(@RequestBody PresetRequest req,
                                   @RequestParam double spot) {
    return strategyService.buildPreset(req, spot);
}
}