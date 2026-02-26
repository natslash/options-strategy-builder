package com.natslash.options_strategy_builder.controller;

import tools.jackson.databind.ObjectMapper;
import com.natslash.options_strategy_builder.model.*;
import com.natslash.options_strategy_builder.service.StrategyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StrategyController.class)
class StrategyControllerTest {

    @Autowired MockMvc     mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean StrategyService strategyService;

    @Test
    void getPresets_returnsAllTenPresets() throws Exception {
        mockMvc.perform(get("/api/strategy/presets"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.length()").value(PresetStrategy.values().length))
               .andExpect(jsonPath("$[0].key").isNotEmpty())
               .andExpect(jsonPath("$[0].name").isNotEmpty());
    }

    @Test
    void getPresets_containsIronCondor() throws Exception {
        mockMvc.perform(get("/api/strategy/presets"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[?(@.key == 'IRON_CONDOR')].name")
                       .value("Iron Condor"));
    }

    @Test
    void analyze_returnsAnalysisFromService() throws Exception {
        StrategyLeg leg = new StrategyLeg();
        leg.setExpiry("20250320");
        leg.setStrike(4800);
        leg.setType("P");
        leg.setDirection("SHORT");
        leg.setQuantity(1);
        leg.setPremium(50.0);

        StrategyRequest request = new StrategyRequest("Short Put", 5000.0, null, List.of(leg));

        StrategyAnalysis mockAnalysis = StrategyAnalysis.builder()
                .name("Short Put")
                .spot(5000.0)
                .netDelta(0.2)
                .netGamma(-0.001)
                .netTheta(-5.0)
                .netVega(0.5)
                .netPremium(500.0)
                .pnlAtExpiry(new TreeMap<>(Map.of(5000, 500.0)))
                .maxProfit(500.0)
                .maxLoss(0.0)
                .breakEvenLow(4750.0)
                .breakEvenHigh(null)
                .legs(List.of(leg))
                .build();

        when(strategyService.analyze(any())).thenReturn(mockAnalysis);

        mockMvc.perform(post("/api/strategy/analyze")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(objectMapper.writeValueAsString(request)))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.name").value("Short Put"))
               .andExpect(jsonPath("$.netPremium").value(500.0))
               .andExpect(jsonPath("$.maxProfit").value(500.0))
               .andExpect(jsonPath("$.breakEvenLow").value(4750.0));
    }

    @Test
    void analyze_acceptsJsonBody() throws Exception {
        String json = """
                {
                  "name": "Test",
                  "spot": 5000.0,
                  "legs": [{
                    "expiry": "20250320",
                    "strike": 5000,
                    "type": "C",
                    "direction": "LONG",
                    "quantity": 1,
                    "premium": 30.0
                  }]
                }
                """;

        when(strategyService.analyze(any())).thenReturn(
                StrategyAnalysis.builder()
                        .name("Test").spot(5000).netDelta(0.3)
                        .netGamma(0).netTheta(0).netVega(0).netPremium(-300)
                        .pnlAtExpiry(Map.of()).maxProfit(0).maxLoss(-300)
                        .legs(List.of()).build());

        mockMvc.perform(post("/api/strategy/analyze")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(json))
               .andExpect(status().isOk());
    }
}
