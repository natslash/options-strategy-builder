package com.natslash.options_strategy_builder.controller;

import com.ib.client.Contract;
import com.ib.client.Types;
import com.natslash.options_strategy_builder.entity.Instrument;
import com.natslash.options_strategy_builder.model.ChainParams;
import com.natslash.options_strategy_builder.model.DiagnosticTickData;
import com.natslash.options_strategy_builder.model.TradingClassParams;
import com.natslash.options_strategy_builder.repository.InstrumentRepository;
import com.natslash.options_strategy_builder.service.IbkrClientService;
import com.natslash.options_strategy_builder.service.OptionsChainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Diagnostic endpoint for inspecting live IBKR tick delivery.
 * Fetches a single option contract tick and returns raw data alongside
 * the MDT mode IBKR actually confirmed and the reason the future completed.
 *
 * <p>Intended for development and operational debugging only — not part of
 * the production chain-fetch flow.
 */
@Slf4j
@RestController
@RequestMapping("/api/diagnostics")
@RequiredArgsConstructor
public class DiagnosticsController {

    private static final int TICK_TIMEOUT_MS = 6000;

    private static final Map<String, Integer> MODE_MAP = Map.of(
            "LIVE",           1,
            "FROZEN",         2,
            "DELAYED",        3,
            "DELAYED_FROZEN", 4
    );

    private final IbkrClientService   ibkr;
    private final InstrumentRepository instrumentRepository;
    private final OptionsChainService  chainService;

    /**
     * GET /api/diagnostics/tick
     *
     * <p>Params:
     * <ul>
     *   <li>{@code instrumentId} — saved instrument id</li>
     *   <li>{@code expiry} — contract expiry in yyyyMMdd format</li>
     *   <li>{@code strike} — option strike price</li>
     *   <li>{@code right} — "C" or "P"</li>
     *   <li>{@code mode} — LIVE | FROZEN | DELAYED | DELAYED_FROZEN</li>
     * </ul>
     *
     * <p>Response includes the confirmed MDT that IBKR used for this subscription
     * (may differ from requested), the completion reason, and all tick fields.
     */
    @GetMapping("/tick")
    public ResponseEntity<Map<String, Object>> testTick(
            @RequestParam Long   instrumentId,
            @RequestParam String expiry,
            @RequestParam double strike,
            @RequestParam String right,
            @RequestParam(defaultValue = "DELAYED_FROZEN") String mode) {

        Instrument instrument = instrumentRepository.findById(instrumentId)
                .orElse(null);
        if (instrument == null)
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Instrument not found: " + instrumentId));

        Integer mdtCode = MODE_MAP.get(mode.toUpperCase());
        if (mdtCode == null)
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Unknown mode: " + mode + ". Use LIVE|FROZEN|DELAYED|DELAYED_FROZEN"));

        // Resolve tradingClass from cached params (avoids a redundant reqSecDefOptParams round-trip)
        String tradingClass = instrument.getTradingClass(); // default fallback
        try {
            ChainParams params = chainService.chainParamsFor(instrument);
            tradingClass = params.forExpiry(expiry)
                    .map(TradingClassParams::tradingClass)
                    .orElse(tradingClass);
        } catch (Exception e) {
            log.warn("Could not resolve tradingClass for expiry={} — using instrument default '{}': {}",
                    expiry, tradingClass, e.getMessage());
        }

        Contract contract = buildContract(instrument, expiry, strike, right, tradingClass);
        log.info("Diagnostics tick: instrumentId={} expiry={} strike={} right={} mode={} tradingClass={}",
                instrumentId, expiry, strike, right, mode, tradingClass);

        try {
            ibkr.reqMarketDataType(mdtCode);
            DiagnosticTickData result = ibkr.reqMktDataDiagnostic(contract, TICK_TIMEOUT_MS).join();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("request", Map.of(
                    "instrumentId", instrumentId,
                    "expiry",        expiry,
                    "strike",        strike,
                    "right",         right,
                    "mode",          mode,
                    "tradingClass",  tradingClass
            ));
            response.put("ibkrMode", Map.of(
                    "requested",  mode,
                    "confirmedCode",  result.confirmedMdt() != null ? result.confirmedMdt() : "N/A",
                    "confirmedLabel", mdtLabel(result.confirmedMdt())
            ));
            response.put("completion", result.completionReason());
            response.put("hasData", result.tick().hasData());
            // Map.ofEntries / Map.entry reject null values — use LinkedHashMap for nullable tick fields.
            Map<String, Object> tickMap = new LinkedHashMap<>();
            tickMap.put("bid",           result.tick().bid());
            tickMap.put("ask",           result.tick().ask());
            tickMap.put("last",          result.tick().last());
            tickMap.put("close",         result.tick().close());
            tickMap.put("iv",            result.tick().impliedVol() != null
                                             ? result.tick().impliedVol() * 100.0 : null);
            tickMap.put("delta",         result.tick().delta());
            tickMap.put("gamma",         result.tick().gamma());
            tickMap.put("theta",         result.tick().theta());
            tickMap.put("vega",          result.tick().vega());
            tickMap.put("undPrice",      result.tick().undPrice());
            tickMap.put("greeksReceived", result.tick().greeksReceived());
            tickMap.put("bidSize",       result.tick().bidSize());
            tickMap.put("askSize",       result.tick().askSize());
            tickMap.put("volume",        result.tick().volume());
            response.put("tick", tickMap);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Diagnostics tick failed: {}", e.getMessage(), e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", msg));
        }
    }

    private Contract buildContract(Instrument instrument, String expiry,
                                   double strike, String right, String tradingClass) {
        Contract c = new Contract();
        c.symbol(instrument.getSymbol());
        c.secType("OPT");
        c.exchange(instrument.getExchange());
        c.currency(instrument.getCurrency());
        c.lastTradeDateOrContractMonth(expiry);
        c.strike(strike);
        c.right("C".equalsIgnoreCase(right) ? Types.Right.Call : Types.Right.Put);
        c.multiplier(String.valueOf(instrument.getMultiplier()));
        c.tradingClass(tradingClass);
        return c;
    }

    private String mdtLabel(Integer code) {
        if (code == null) return "N/A";
        return switch (code) {
            case 1 -> "LIVE";
            case 2 -> "FROZEN";
            case 3 -> "DELAYED";
            case 4 -> "DELAYED_FROZEN";
            default -> "UNKNOWN(" + code + ")";
        };
    }
}
