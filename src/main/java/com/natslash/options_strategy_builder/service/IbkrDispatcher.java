package com.natslash.options_strategy_builder.service;

import com.ib.client.*;
import com.natslash.options_strategy_builder.model.ChainParams;
import com.natslash.options_strategy_builder.model.TickData;
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
    public CompletableFuture<ChainParams> reqChainParams(String symbol, String secType, int conId) {
        int reqId = nextReqId();
        ChainParamsAccumulator acc = new ChainParamsAccumulator();
        chainParamMap.put(reqId, acc);

        log.info("reqSecDefOptParams reqId={} symbol={} conId={}", reqId, symbol, conId);
        client.reqSecDefOptParams(reqId, symbol, "", secType, conId);

        return acc.future
                .orTimeout(30, TimeUnit.SECONDS)
                .thenApply(a -> {
                    log.info("Chain params: {} expiries, {} strikes", a.expirations.size(), a.strikes.size());
                    return new ChainParams(new ArrayList<>(a.expirations), new ArrayList<>(a.strikes));
                })
                .whenComplete((r, ex) -> {
                    if (ex != null)
                        log.warn("reqChainParams failed: {}", ex.getMessage());
                    chainParamMap.remove(reqId);
                });
    }

    /**
     * Requests market data for a specific contract and gathers results over a time
     * window.
     * * @param contract The option or index contract to fetch
     * 
     * @param timeoutMs The window (e.g., 1000-1500ms) to wait for the data burst to
     *                  complete
     * @return A future containing the aggregated TickData
     */
    public CompletableFuture<TickData> reqMktData(Contract contract, int timeoutMs) {
        int reqId = reqIdCounter.getAndIncrement();
        TickAccumulator acc = new TickAccumulator();
        tickMap.put(reqId, acc);

        // FIX: snapshot=false allows the use of generic tick '101'.
        // Generic tick '101' (Option PV Dividend) forces IBKR to calculate the
        // internal Greeks model (Field 13) even when the market is closed.
        client.reqMktData(reqId, contract, "101", false, false, Collections.emptyList());

        // Wait the full timeout window so Bid/Ask and Model Greeks all arrive before mapping.
        // tickSnapshotEnd or error callbacks may complete the future earlier if applicable.
        acc.future.completeOnTimeout(acc, timeoutMs, TimeUnit.MILLISECONDS);

        return acc.future
                .thenApply(this::mapToTickData)
                .whenComplete((r, ex) -> {
                    client.cancelMktData(reqId);
                    tickMap.remove(reqId);
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
        if (acc == null)
            return;
        acc.expirations.addAll(expirations);
        acc.strikes.addAll(strikes);
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
            case 1 -> acc.bid = price;
            case 2 -> acc.ask = price;
            case 4 -> acc.last = price;
            case 9 -> acc.close = price;
        }
    }

    @Override
    public void tickSize(int reqId, int field, Decimal size) {
        TickAccumulator acc = tickMap.get(reqId);
        if (acc == null)
            return;
        switch (field) {
            case 0 -> acc.bidSize = (int) size.longValue();
            case 3 -> acc.askSize = (int) size.longValue();
            case 8 -> acc.volume = (int) size.longValue();
            case 22 -> acc.openInterest = (int) size.longValue();
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

        // Field 13 = Model calculation (Server-side Black-Scholes)
        // Field 10/11 = Bid/Ask based Greeks (Only available during market hours)
        if (field == 13 || field == 10 || field == 11) {
            // IBKR returns -1 or -2 for values that aren't yet calculated; we filter those
            // out.
            if (impliedVol > 0)
                acc.impliedVol = impliedVol;
            if (delta >= -1 && delta <= 1)
                acc.delta = delta;
            if (gamma > -2)
                acc.gamma = gamma;
            if (vega > -2)
                acc.vega = vega;
            if (theta > -2)
                acc.theta = theta;
            if (optPrice > 0)
                acc.optPrice = optPrice;
            if (undPrice > 0)
                acc.undPrice = undPrice;

            // Mark that we have at least one successful Greek data point
            acc.greeksReceived = true;

            // NOTE: We no longer call acc.future.complete() here.
            // The reqMktData timeout now handles the completion.
        }
    }

    @Override
    public void tickSnapshotEnd(int reqId) {
        TickAccumulator acc = tickMap.remove(reqId);
        if (acc != null)
            acc.future.complete(acc);
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
            log.error("PERMISSIONS ALERT: ReqId {} is being downgraded to DELAYED. " +
                    "This confirms IBKR does not recognize your real-time subscription " +
                    "for this specific exchange/instrument.", reqId);
        }
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
            if (tickAcc != null)
                tickAcc.future.complete(tickAcc);
            return;
        }
        // Unblock tick futures for any error so they don't wait for timeoutMs
        TickAccumulator tickAcc = tickMap.get(id);
        if (tickAcc != null) {
            log.warn("IBKR tick error id={} code={} msg={}", id, errorCode, errorMsg);
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
        final Set<String> expirations = ConcurrentHashMap.newKeySet();
        final Set<Double> strikes = ConcurrentHashMap.newKeySet();
        final CompletableFuture<ChainParamsAccumulator> future = new CompletableFuture<>();
    }

    static class TickAccumulator {
        volatile Double bid, ask, last, close, optPrice, undPrice;
        volatile Double impliedVol, delta, gamma, vega, theta;
        volatile int bidSize, askSize, volume, openInterest;
        volatile boolean greeksReceived;
        final CompletableFuture<TickAccumulator> future = new CompletableFuture<>();
    }
}
