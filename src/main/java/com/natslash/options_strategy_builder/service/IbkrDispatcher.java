package com.natslash.options_strategy_builder.service;

import com.ib.client.*;
import com.natslash.options_strategy_builder.model.ChainParams;
import com.natslash.options_strategy_builder.model.DiagnosticTickData;
import com.natslash.options_strategy_builder.model.HistoricalBar;
import com.natslash.options_strategy_builder.model.TickData;
import com.natslash.options_strategy_builder.model.TradingClassParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * EWrapper implementation that owns the full IBKR request-response lifecycle:
 * routing callbacks to the correct pending-request accumulator via reqId, and
 * initiating typed requests through the shared EClientSocket.
 *
 * <p>
 * Maps are private — callers interact only through the typed request methods.
 * {@link IbkrClientService} calls {@link #setClient} once the socket is
 * connected.
 */
@Slf4j
@Component
public class IbkrDispatcher extends DefaultEWrapper {

    final AtomicInteger reqIdCounter = new AtomicInteger(1);
    CompletableFuture<Void> connectFuture;
    private EClientSocket client;

    // ── Pending-request registries ─────────────────────────────
    private final Map<Integer, ContractDetailsAccumulator> contractDetailsMap = new ConcurrentHashMap<>();
    private final Map<Integer, ChainParamsAccumulator> chainParamMap = new ConcurrentHashMap<>();
    private final Map<Integer, TickAccumulator> tickMap = new ConcurrentHashMap<>();
    private final Map<Integer, HistoricalDataAccumulator> historicalDataMap = new ConcurrentHashMap<>();

    // ── Connection lifecycle ───────────────────────────────────

    void prepareConnect() {
        connectFuture = new CompletableFuture<>();
    }

    /**
     * Called by {@link IbkrClientService} immediately after the socket connects.
     */
    void setClient(EClientSocket client) {
        this.client = client;
    }

    int nextReqId() {
        return reqIdCounter.getAndIncrement();
    }

    @Override
    public void nextValidId(int orderId) {
        log.info("nextValidId={} — connection established", orderId);
        reqIdCounter.set(Math.max(orderId, reqIdCounter.get()));
        connectFuture.complete(null);
    }

    // ── Requests ──────────────────────────────────────────────

    public void reqMarketDataType(int type) {
        client.reqMarketDataType(type);
        log.info("Market data type set to {}", type);
    }

    /**
     * Returns a future that completes with matching ContractDetails, or
     * completes exceptionally with TimeoutException after 15 s.
     */
    public CompletableFuture<List<ContractDetails>> reqContractDetails(String symbol, String secType) {
        int reqId = nextReqId();
        ContractDetailsAccumulator acc = new ContractDetailsAccumulator();
        contractDetailsMap.put(reqId, acc);

        Contract c = new Contract();
        c.symbol(symbol);
        c.secType(secType);

        log.info("reqContractDetails reqId={} symbol={} secType={}", reqId, symbol, secType);
        client.reqContractDetails(reqId, c);

        return acc.future
                .orTimeout(15, TimeUnit.SECONDS)
                .thenApply(a -> {
                    log.info("Contract details: {} results for {}", a.results.size(), symbol);
                    List<ContractDetails> list = new ArrayList<>(a.results);
                    return list;
                })
                .whenComplete((r, ex) -> {
                    if (ex != null)
                        log.warn("reqContractDetails failed for {}: {}", symbol, ex.getMessage());
                    contractDetailsMap.remove(reqId);
                });
    }

    /**
     * Returns a future that completes with ChainParams, or completes
     * exceptionally with TimeoutException after 30 s.
     */
    public CompletableFuture<ChainParams> reqChainParams(String symbol, String secType, int conId, String exchange) {
        int reqId = nextReqId();
        ChainParamsAccumulator acc = new ChainParamsAccumulator(exchange);
        chainParamMap.put(reqId, acc);

        log.info("reqSecDefOptParams reqId={} symbol={} conId={}", reqId, symbol, conId);
        client.reqSecDefOptParams(reqId, symbol, "", secType, conId);

        return acc.future
                .orTimeout(30, TimeUnit.SECONDS)
                .thenApply(a -> {
                    List<TradingClassParams> tradingClasses = a.byTradingClass.entrySet().stream()
                            .map(e -> new TradingClassParams(
                                    e.getKey(),
                                    e.getValue().expirations.stream().sorted().toList(),
                                    e.getValue().strikes.stream().sorted().toList()))
                            .toList();
                    if (tradingClasses.isEmpty())
                        log.warn("reqChainParams for {} returned 0 tradingClasses from exchange='{}' — " +
                                "IBKR may use a different exchange name in its callback; check logs above",
                                symbol, acc.targetExchange);
                    tradingClasses.forEach(tc -> log.info(
                            "Chain params (exchange={} tradingClass={}): {} expiries, {} strikes",
                            acc.targetExchange, tc.tradingClass(), tc.expirations().size(), tc.strikes().size()));
                    return new ChainParams(tradingClasses);
                })
                .whenComplete((r, ex) -> {
                    if (ex != null)
                        log.warn("reqChainParams failed: {}", ex.getMessage());
                    chainParamMap.remove(reqId);
                });
    }

    /**
     * Requests market data for an option contract.
     *
     * <p>Streaming mode (snapshot=false) with generic ticks "100,101,106". Tick 106 (Option IV)
     * triggers IBKR's server-side Black-Scholes model, emitting Greeks as field 13 (MODEL_OPTION)
     * during live hours or field 83 (DELAYED_MODEL_OPTION) with MDT=4. The {@code completeOnTimeout}
     * window collects the full data burst. {@code tickSnapshotEnd} is intentionally ignored — it
     * fires as an IBKR quirk before Greeks arrive in streaming mode.
     *
     * <p>NOTE: snapshot=true is intentionally not supported here. IBKR error 321 rejects any
     * combination of snapshot=true + non-empty genericTickList. Streaming mode handles both live
     * and delayed (MDT=4) Greeks reliably within the timeout window.
     */
    public CompletableFuture<TickData> reqMktData(Contract contract, int timeoutMs) {
        return reqMktDataInternal(contract, timeoutMs, "100,101,106");
    }

    /**
     * Requests market data for an underlying (index/futures) contract — spot price only.
     *
     * <p>Uses empty genericTickList (underlying contracts are not options; option-specific
     * generic ticks 100/101/106 are unnecessary). Price arrives via {@code tickPrice} callbacks:
     * field 4 (last) or field 9 (close). Works in both live (MDT=1) and delayed-frozen (MDT=4) modes.
     */
    public CompletableFuture<TickData> reqUnderlyingPrice(Contract contract, int timeoutMs) {
        return reqMktDataInternal(contract, timeoutMs, "");
    }

    /**
     * Diagnostic variant of {@link #reqMktData} — returns an enriched result that includes
     * the MDT mode IBKR actually confirmed for the subscription and the reason the future
     * completed (EARLY = Greeks+price arrived, TIMEOUT = window expired, ERROR_XXX = IBKR error).
     */
    public CompletableFuture<DiagnosticTickData> reqMktDataDiagnostic(Contract contract, int timeoutMs) {
        int reqId = nextReqId();
        TickAccumulator acc = new TickAccumulator(true);
        tickMap.put(reqId, acc);
        client.reqMktData(reqId, contract, "100,101,106", false, false, Collections.emptyList());
        acc.future.completeOnTimeout(acc, timeoutMs, TimeUnit.MILLISECONDS);
        return acc.future
                .thenApply(a -> new DiagnosticTickData(
                        mapToTickData(a),
                        a.confirmedMdt,
                        a.completionReason != null ? a.completionReason : "TIMEOUT"))
                .whenComplete((r, ex) -> {
                    client.cancelMktData(reqId);
                    tickMap.remove(reqId);
                });
    }

    private CompletableFuture<TickData> reqMktDataInternal(Contract contract, int timeoutMs, String genericTicks) {
        int reqId = nextReqId();
        TickAccumulator acc = new TickAccumulator(!genericTicks.isEmpty());
        tickMap.put(reqId, acc);

        client.reqMktData(reqId, contract, genericTicks, false, false, Collections.emptyList());
        acc.future.completeOnTimeout(acc, timeoutMs, TimeUnit.MILLISECONDS);

        return acc.future
                .thenApply(this::mapToTickData)
                .whenComplete((r, ex) -> {
                    client.cancelMktData(reqId);
                    tickMap.remove(reqId);
                });
    }

    /**
     * Requests historical data for a contract and collects bars until the end callback.
     * Completes with all bars or times out after 30 s.
     */
    public CompletableFuture<List<HistoricalBar>> reqHistoricalData(
            Contract contract, String duration, String barSize, String whatToShow) {
        int reqId = nextReqId();
        HistoricalDataAccumulator acc = new HistoricalDataAccumulator();
        historicalDataMap.put(reqId, acc);

        log.info("reqHistoricalData reqId={} duration={} barSize={} whatToShow={}", reqId, duration, barSize, whatToShow);
        // useRTH=1 (regular trading hours), formatDate=1
        client.reqHistoricalData(reqId, contract, "", duration, barSize, whatToShow, 1, 1, false, Collections.emptyList());

        return acc.future
                .orTimeout(30, TimeUnit.SECONDS)
                .<List<HistoricalBar>>thenApply(a -> new ArrayList<>(a.bars))
                .whenComplete((r, ex) -> {
                    if (ex != null)
                        log.warn("reqHistoricalData failed reqId={}: {}", reqId, ex.getMessage());
                    historicalDataMap.remove(reqId);
                });
    }

    // ── Contract details callbacks ─────────────────────────────

    @Override
    public void contractDetails(int reqId, ContractDetails contractDetails) {
        ContractDetailsAccumulator acc = contractDetailsMap.get(reqId);
        if (acc != null)
            acc.results.add(contractDetails);
    }

    @Override
    public void contractDetailsEnd(int reqId) {
        ContractDetailsAccumulator acc = contractDetailsMap.remove(reqId);
        if (acc != null)
            acc.future.complete(acc);
    }

    // ── Chain params callbacks ─────────────────────────────────

    @Override
    public void securityDefinitionOptionalParameter(
            int reqId, String exchange, int underlyingConId,
            String tradingClass, String multiplier,
            Set<String> expirations, Set<Double> strikes) {
        ChainParamsAccumulator acc = chainParamMap.get(reqId);
        // Only use strikes from the target exchange. IBKR fires one callback per exchange
        // (e.g. EUREX, SMART). SMART includes a theoretical superset — mixing it with
        // EUREX results in 5-point strikes that don't exist as actual EUREX contracts,
        // producing error 200 on every reqMktData for those strikes.
        // Within the target exchange, keep each tradingClass separate (e.g. OESX monthly
        // at 25pt vs OESXW weekly at finer intervals) — merging them creates the same
        // superset problem across series.
        log.debug("secDefOptParam reqId={} exchange={} tradingClass={} (target={}) strikes={}",
                reqId, exchange, tradingClass, acc == null ? "n/a" : acc.targetExchange, strikes.size());
        if (acc == null || !acc.targetExchange.equalsIgnoreCase(exchange))
            return;
        ChainParamsAccumulator.TcEntry entry =
                acc.byTradingClass.computeIfAbsent(tradingClass, k -> new ChainParamsAccumulator.TcEntry());
        entry.expirations.addAll(expirations);
        entry.strikes.addAll(strikes);
    }

    @Override
    public void securityDefinitionOptionalParameterEnd(int reqId) {
        ChainParamsAccumulator acc = chainParamMap.remove(reqId);
        if (acc != null)
            acc.future.complete(acc);
    }

    // ── Market data callbacks ──────────────────────────────────

    @Override
    public void tickPrice(int reqId, int field, double price, TickAttrib attrib) {
        TickAccumulator acc = tickMap.get(reqId);
        if (acc == null || price <= 0)
            return;
        switch (field) {
            case 1,  68 -> acc.bid   = price;  // BID / DELAYED_BID
            case 2,  69 -> acc.ask   = price;  // ASK / DELAYED_ASK
            case 4,  70 -> acc.last  = price;  // LAST / DELAYED_LAST
            case 9,  75 -> acc.close = price;  // CLOSE / DELAYED_CLOSE
        }
        tryCompleteEarly(acc);
    }

    @Override
    public void tickSize(int reqId, int field, Decimal size) {
        TickAccumulator acc = tickMap.get(reqId);
        if (acc == null)
            return;
        switch (field) {
            case 0,  66 -> acc.bidSize      = (int) size.longValue();  // BID_SIZE / DELAYED_BID_SIZE
            case 3,  67 -> acc.askSize      = (int) size.longValue();  // ASK_SIZE / DELAYED_ASK_SIZE
            case 8,  74 -> acc.volume       = (int) size.longValue();  // VOLUME / DELAYED_VOLUME
            case 22      -> acc.openInterest = (int) size.longValue();  // OPTION_OPEN_INTEREST
        }
    }

    /**
     * Callback for option-specific calculations (Greeks and Model IV).
     * During off-hours, we specifically look for Field 13 (MODEL_OPTION).
     */
    @Override
    public void tickOptionComputation(int tickerId, int field, int tickAttrib, double impliedVol,
            double delta, double optPrice, double pvDividend, double gamma,
            double vega, double theta, double undPrice) {
        TickAccumulator acc = tickMap.get(tickerId);
        if (acc == null)
            return;

        // Real-time (MDT 1/2): 10=BID_OPTION, 11=ASK_OPTION, 13=MODEL_OPTION
        // Delayed  (MDT 3/4): 80=DELAYED_BID, 81=DELAYED_ASK, 83=DELAYED_MODEL_OPTION
        // Field 83 is the primary carrier of off-hours Greeks when no real-time subscription exists.
        if (field == 13 || field == 83 ||
                field == 10 || field == 80 ||
                field == 11 || field == 81) {
            // IBKR returns -1/-2 for uncalculated values, Double.MAX_VALUE as a sentinel
            // for "not available" (e.g. vega/theta/gamma when the model hasn't converged),
            // and Double.POSITIVE_INFINITY for IV on deep-ITM/OTM options.
            // Note: Double.isFinite(Double.MAX_VALUE) == true — MAX_VALUE is a valid finite
            // number and must be excluded explicitly.
            if (impliedVol > 0 && Double.isFinite(impliedVol) && impliedVol != Double.MAX_VALUE)
                acc.impliedVol = impliedVol;
            if (delta >= -1 && delta <= 1)    // bounds already exclude MAX_VALUE / Infinity
                acc.delta = delta;
            if (gamma > -2 && Double.isFinite(gamma) && gamma != Double.MAX_VALUE)
                acc.gamma = gamma;
            if (vega  > -2 && Double.isFinite(vega)  && vega  != Double.MAX_VALUE)
                acc.vega = vega;
            if (theta > -2 && Double.isFinite(theta) && theta != Double.MAX_VALUE)
                acc.theta = theta;
            if (optPrice > 0 && Double.isFinite(optPrice) && optPrice != Double.MAX_VALUE)
                acc.optPrice = optPrice;
            if (undPrice > 0 && Double.isFinite(undPrice) && undPrice != Double.MAX_VALUE)
                acc.undPrice = undPrice;

            // IBKR fires up to three tickOptionComputation callbacks per contract:
            //   field 10 = BID_OPTION  (Greeks from bid price)
            //   field 11 = ASK_OPTION  (Greeks from ask price)
            //   field 13 = MODEL_OPTION (server B-S model — may send MAX_VALUE for gamma/vega/theta
            //                            when the model hasn't converged)
            //
            // Completing early on delta alone (from field 13) causes us to miss field 10/11
            // which arrive later and carry the valid gamma/vega/theta. Require both delta AND
            // gamma to be non-null before marking Greeks as complete — gamma being non-null
            // confirms that a proper full computation (not just a delta-only model tick) has
            // arrived.
            if (acc.delta != null && acc.gamma != null) acc.greeksReceived = true;
            tryCompleteEarly(acc);
        }
    }

    /**
     * Completes the accumulator's future early if enough data has arrived,
     * saving the remainder of the WINDOW_MS timeout.
     *
     * Option contracts: complete when Greeks AND at least one price field are both present.
     * Underlying contracts: complete as soon as last or close arrives (no Greeks expected).
     */
    private void tryCompleteEarly(TickAccumulator acc) {
        if (acc.expectGreeks) {
            if (acc.greeksReceived && (acc.bid != null || acc.ask != null
                    || acc.last != null || acc.close != null)) {
                acc.completionReason = "EARLY";
                acc.future.complete(acc);
            }
        } else {
            // Cash indices (e.g. SPX, ESTX50 IND) have no traded last price — IBKR only
            // sends bid/ask. Complete as soon as we have any usable price.
            if (acc.last != null || acc.close != null
                    || (acc.bid != null && acc.ask != null)) {
                acc.completionReason = "EARLY";
                acc.future.complete(acc);
            }
        }
    }

    @Override
    public void tickSnapshotEnd(int reqId) {
        // All requests use streaming mode (snapshot=false). tickSnapshotEnd is a known IBKR
        // quirk that can fire before tickOptionComputation in streaming mode.
        // Ignore it — completeOnTimeout owns completion.
    }

    @Override
    public void marketDataType(int reqId, int marketDataType) {
        // 1 = Live (Real-time)
        // 2 = Frozen (Last-known price from close)
        // 3 = Delayed (15-min lag)
        // 4 = Delayed Frozen (Last-known price from delayed stream)

        String typeStr = switch (marketDataType) {
            case 1 -> "LIVE";
            case 2 -> "FROZEN";
            case 3 -> "DELAYED";
            case 4 -> "DELAYED_FROZEN";
            default -> "UNKNOWN (" + marketDataType + ")";
        };

        log.debug(">>> SUBSCRIPTION CHECK: ReqId {} is receiving {} data", reqId, typeStr);

        if (marketDataType >= 3) {
            log.info("ReqId {} using {} data — no real-time subscription for this instrument; " +
                    "delayed Greeks will arrive as fields 80/81/83 instead of 10/11/13.", reqId, typeStr);
        }

        // Store confirmed mode so diagnostic callers can surface it in responses
        TickAccumulator acc = tickMap.get(reqId);
        if (acc != null) acc.confirmedMdt = marketDataType;
    }

    // ── Error handling ─────────────────────────────────────────

    @Override
    public void error(int id, long errorTime, int errorCode, String errorMsg, String advancedOrderRejectJson) {
        if (isSuppress(errorCode))
            return;
        if (errorCode == 200) {
            // Contract not found — unblock waiting futures with empty/partial results
            ContractDetailsAccumulator cdAcc = contractDetailsMap.remove(id);
            if (cdAcc != null)
                cdAcc.future.complete(cdAcc);
            TickAccumulator tickAcc = tickMap.remove(id);
            if (tickAcc != null) {
                log.debug("Error 200 (no security definition) for tick reqId={} — " +
                        "strike likely not an active contract in IBKR for this expiry/tradingClass", id);
                tickAcc.completionReason = "ERROR_200";
                tickAcc.future.complete(tickAcc);
            }
            return;
        }
        // Unblock tick futures for any error so they don't wait for timeoutMs
        TickAccumulator tickAcc = tickMap.get(id);
        if (tickAcc != null) {
            log.warn("IBKR tick error id={} code={} msg={}", id, errorCode, errorMsg);
            tickAcc.completionReason = "ERROR_" + errorCode;
            tickMap.remove(id);
            tickAcc.future.complete(tickAcc);
            return;
        }
        log.warn("IBKR error id={} code={} msg={}", id, errorCode, errorMsg);
    }

    @Override
    public void error(String str) {
        log.warn("IBKR: {}", str);
    }

    @Override
    public void error(Exception e) {
        log.error("IBKR exception", e);
    }

    @Override
    public void connectionClosed() {
        log.warn("IBKR connection closed");
    }

    // ── Historical data callbacks ──────────────────────────────

    @Override
    public void historicalData(int reqId, Bar bar) {
        HistoricalDataAccumulator acc = historicalDataMap.get(reqId);
        if (acc != null)
            acc.bars.add(new HistoricalBar(bar.time(), bar.open(), bar.high(), bar.low(), bar.close()));
    }

    @Override
    public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) {
        HistoricalDataAccumulator acc = historicalDataMap.remove(reqId);
        if (acc != null)
            acc.future.complete(acc);
    }

    private TickData mapToTickData(TickAccumulator a) {
        return new TickData(
                a.bid, a.ask, a.last, a.close, a.optPrice, a.undPrice,
                a.impliedVol, a.delta, a.gamma, a.vega, a.theta,
                a.bidSize, a.askSize, a.volume, a.openInterest, a.greeksReceived);
    }

    private boolean isSuppress(long code) {
        return code == 2104 || code == 2106 || code == 2158 || code == 2119
                || code == 2103 || code == 2105 || code == 2107 || code == 2108
                || code == 10167 || code == 10090 || code == 300;
    }

    // ── Accumulators ───────────────────────────────────────────

    static class ContractDetailsAccumulator {
        final List<ContractDetails> results = Collections.synchronizedList(new ArrayList<>());
        final CompletableFuture<ContractDetailsAccumulator> future = new CompletableFuture<>();
    }

    static class ChainParamsAccumulator {
        final Map<String, TcEntry> byTradingClass = new ConcurrentHashMap<>();
        final CompletableFuture<ChainParamsAccumulator> future = new CompletableFuture<>();
        final String targetExchange;

        ChainParamsAccumulator(String targetExchange) { this.targetExchange = targetExchange; }

        static class TcEntry {
            final Set<String> expirations = ConcurrentHashMap.newKeySet();
            final Set<Double>  strikes     = ConcurrentHashMap.newKeySet();
        }
    }

    static class TickAccumulator {
        final boolean expectGreeks;
        volatile Double bid, ask, last, close, optPrice, undPrice;
        volatile Double impliedVol, delta, gamma, vega, theta;
        volatile int bidSize, askSize, volume, openInterest;
        volatile boolean greeksReceived;
        final CompletableFuture<TickAccumulator> future = new CompletableFuture<>();
        volatile Integer confirmedMdt;      // set by marketDataType callback
        volatile String  completionReason;  // EARLY | TIMEOUT | ERROR_200 | ERROR_XXX

        TickAccumulator(boolean expectGreeks) { this.expectGreeks = expectGreeks; }
    }

    static class HistoricalDataAccumulator {
        final List<HistoricalBar> bars = Collections.synchronizedList(new ArrayList<>());
        final CompletableFuture<HistoricalDataAccumulator> future = new CompletableFuture<>();
    }
}
