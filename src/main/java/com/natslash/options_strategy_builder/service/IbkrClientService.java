package com.natslash.options_strategy_builder.service;

import com.ib.client.*;
import com.natslash.options_strategy_builder.config.IbkrProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class IbkrClientService {

    private final IbkrProperties props;
    private final Handler handler;
    private EClientSocket client;
    private EReaderSignal signal;
    private CountDownLatch connectLatch;

    public IbkrClientService(IbkrProperties props) {
        this.props = props;
        this.handler = new Handler();
    }

    // ═══════════════════════════════════════════════════════════
    // Connection
    // ═══════════════════════════════════════════════════════════

    public void connect() throws InterruptedException {
        connectLatch = new CountDownLatch(1);
        signal = new EJavaSignal();
        client = new EClientSocket(handler, signal);
        handler.setClient(client);
        client.eConnect(props.getHost(), props.getPort(), props.getClientId());

        EReader reader = new EReader(client, signal);
        reader.start();
        Thread.ofVirtual().start(() -> {
            while (client.isConnected()) {
                signal.waitForSignal();
                try {
                    reader.processMsgs();
                } catch (Exception e) {
                    log.error("Reader error", e);
                }
            }
        });

        if (!connectLatch.await(10, TimeUnit.SECONDS))
            throw new RuntimeException("Timed out waiting for IBKR connection");
        log.info("Connected to IB Gateway {}:{}", props.getHost(), props.getPort());
    }

    @PostConstruct
    public void init() throws InterruptedException {
        connect();
    }

    @PreDestroy
    public void disconnect() {
        if (client != null && client.isConnected()) {
            client.eDisconnect();
            log.info("Disconnected from IB Gateway");
        }
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    public int nextReqId() {
        return handler.reqIdCounter.getAndIncrement();
    }

    public void reqMarketDataType(int type) {
        client.reqMarketDataType(type);
        log.info("Market data type set to {}", type);
    }

    // ═══════════════════════════════════════════════════════════
    // Contract details search
    // ═══════════════════════════════════════════════════════════

    /**
     * Search IBKR for contracts matching symbol + secType.
     * Use secType="IND" for indices, "STK" for stocks.
     */
    public List<ContractDetails> reqContractDetails(String symbol, String secType)
            throws InterruptedException, TimeoutException {

        int reqId = nextReqId();
        Handler.ContractDetailsAccumulator acc = new Handler.ContractDetailsAccumulator();
        handler.contractDetailsMap.put(reqId, acc);

        Contract c = new Contract();
        c.symbol(symbol);
        c.secType(secType);

        log.info("reqContractDetails reqId={} symbol={} secType={}", reqId, symbol, secType);
        client.reqContractDetails(reqId, c);

        boolean ok = acc.latch.await(15, TimeUnit.SECONDS);
        handler.contractDetailsMap.remove(reqId);
        if (!ok)
            throw new TimeoutException("reqContractDetails timed out for " + symbol);

        log.info("Contract details: {} results for {}", acc.results.size(), symbol);
        return new ArrayList<>(acc.results);
    }

    // ═══════════════════════════════════════════════════════════
    // Chain params
    // ═══════════════════════════════════════════════════════════

    public ChainParams reqChainParams(String symbol, String secType, int conId)
            throws InterruptedException, TimeoutException {

        int reqId = nextReqId();
        Handler.ChainParamsAccumulator acc = new Handler.ChainParamsAccumulator();
        handler.chainParamMap.put(reqId, acc);

        log.info("reqSecDefOptParams reqId={} symbol={} conId={}", reqId, symbol, conId);
        client.reqSecDefOptParams(reqId, symbol, "", secType, conId);

        boolean ok = acc.latch.await(30, TimeUnit.SECONDS);
        handler.chainParamMap.remove(reqId);
        if (!ok)
            throw new TimeoutException("reqSecDefOptParams timed out");

        log.info("Chain params: {} expiries, {} strikes", acc.expirations.size(), acc.strikes.size());
        return new ChainParams(new ArrayList<>(acc.expirations), new ArrayList<>(acc.strikes));
    }

    // ═══════════════════════════════════════════════════════════
    // Market data
    // ═══════════════════════════════════════════════════════════

    public TickData reqMktData(Contract contract, int timeoutMs) throws InterruptedException {
        int reqId = nextReqId();
        Handler.TickAccumulator acc = new Handler.TickAccumulator();
        handler.tickMap.put(reqId, acc);

        client.reqMktData(reqId, contract, "", false, false, Collections.emptyList());
        acc.latch.await(timeoutMs, TimeUnit.MILLISECONDS);

        client.cancelMktData(reqId);
        handler.tickMap.remove(reqId);
        return acc.toTickData();
    }

    // ═══════════════════════════════════════════════════════════
    // Records
    // ═══════════════════════════════════════════════════════════

    public record ChainParams(List<String> expirations, List<Double> strikes) {
    }

    public record TickData(
            Double bid, Double ask, Double last, Double close,
            Double optPrice, Double undPrice,
            Double impliedVol, Double delta, Double gamma, Double vega, Double theta,
            int volume, int openInterest, boolean greeksReceived) {

        public boolean hasData() {
            return bid != null || ask != null || last != null || close != null || greeksReceived;
        }

        public Double mid() {
            if (bid != null && ask != null)
                return (bid + ask) / 2.0;
            if (bid != null)
                return bid;
            if (ask != null)
                return ask;
            return last;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Inner EWrapper handler
    // ═══════════════════════════════════════════════════════════

    class Handler extends DefaultEWrapper {

        final AtomicInteger reqIdCounter = new AtomicInteger(1);
        final Map<Integer, ContractDetailsAccumulator> contractDetailsMap = new ConcurrentHashMap<>();
        final Map<Integer, ChainParamsAccumulator> chainParamMap = new ConcurrentHashMap<>();
        final Map<Integer, TickAccumulator> tickMap = new ConcurrentHashMap<>();

        private EClientSocket client;

        void setClient(EClientSocket client) {
            this.client = client;
        }

        @Override
        public void nextValidId(int orderId) {
            log.info("nextValidId={} — connection established", orderId);
            reqIdCounter.set(Math.max(orderId, reqIdCounter.get()));
            connectLatch.countDown();
        }

        // ── Contract details ───────────────────────────────────

        @Override
        public void contractDetails(int reqId, ContractDetails contractDetails) {
            ContractDetailsAccumulator acc = contractDetailsMap.get(reqId);
            if (acc != null)
                acc.results.add(contractDetails);
        }

        @Override
        public void contractDetailsEnd(int reqId) {
            ContractDetailsAccumulator acc = contractDetailsMap.get(reqId);
            if (acc != null)
                acc.latch.countDown();
        }

        // ── Chain params ───────────────────────────────────────

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
            ChainParamsAccumulator acc = chainParamMap.get(reqId);
            if (acc != null)
                acc.latch.countDown();
        }

        // ── Market data ────────────────────────────────────────

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
            if (field == 1 || field == 2 || field == 4 || field == 9)
                acc.onPrice();
        }

        @Override
        public void tickSize(int reqId, int field, Decimal size) {
            TickAccumulator acc = tickMap.get(reqId);
            if (acc == null)
                return;
            switch (field) {
                case 8 -> acc.volume = (int) size.longValue();
                case 22 -> acc.openInterest = (int) size.longValue();
            }
        }

        @Override
        public void tickOptionComputation(
                int reqId, int field, int tickAttrib,
                double impliedVol, double delta, double optPrice,
                double pvDividend, double gamma, double vega, double theta,
                double undPrice) {
            TickAccumulator acc = tickMap.get(reqId);
            if (acc == null)
                return;
            if (impliedVol > 0 && impliedVol < 10 && impliedVol != Double.MAX_VALUE)
                acc.impliedVol = impliedVol;
            if (delta != Double.MAX_VALUE && delta != -Double.MAX_VALUE)
                acc.delta = delta;
            if (gamma != Double.MAX_VALUE && gamma != -Double.MAX_VALUE)
                acc.gamma = gamma;
            if (vega != Double.MAX_VALUE && vega != -Double.MAX_VALUE)
                acc.vega = vega;
            if (theta != Double.MAX_VALUE && theta != -Double.MAX_VALUE)
                acc.theta = theta;
            if (undPrice > 0 && undPrice != Double.MAX_VALUE)
                acc.undPrice = undPrice;
            if (optPrice > 0 && optPrice != Double.MAX_VALUE)
                acc.optPrice = optPrice;
            if (field == 13)
                acc.onGreeks();
        }

        @Override
        public void tickSnapshotEnd(int reqId) {
            TickAccumulator acc = tickMap.get(reqId);
            if (acc != null)
                acc.latch.countDown();
        }

        @Override
        public void error(int id, long errorTime, int errorCode, String errorMsg, String advancedOrderRejectJson) {
            if (isSuppress(errorCode))
                return;
            if (errorCode == 200) {
                // Contract not found — unblock waiting latch
                ContractDetailsAccumulator cdAcc = contractDetailsMap.get(id);
                if (cdAcc != null)
                    cdAcc.latch.countDown();
                tickMap.remove(id);
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

        private boolean isSuppress(long code) {
            return code == 2104 || code == 2106 || code == 2158 || code == 2119
                    || code == 2103 || code == 2105 || code == 2107 || code == 2108
                    || code == 10167 || code == 10090 || code == 300;
        }

        // ── Accumulators ───────────────────────────────────────

        static class ContractDetailsAccumulator {
            final CountDownLatch latch = new CountDownLatch(1);
            final List<ContractDetails> results = Collections.synchronizedList(new ArrayList<>());
        }

        static class ChainParamsAccumulator {
            final CountDownLatch latch = new CountDownLatch(1);
            final Set<String> expirations = ConcurrentHashMap.newKeySet();
            final Set<Double> strikes = ConcurrentHashMap.newKeySet();
        }

        static class TickAccumulator {
            volatile Double bid, ask, last, close, optPrice, undPrice;
            volatile Double impliedVol, delta, gamma, vega, theta;
            volatile int volume, openInterest;
            volatile boolean greeksReceived;
            volatile boolean priceReceived;

            final CountDownLatch latch = new CountDownLatch(1);

            void onPrice() {
                priceReceived = true;
                // wait for tickSnapshotEnd
            }

            void onGreeks() {
                greeksReceived = true;
                // wait for tickSnapshotEnd
            }

            TickData toTickData() {
                return new TickData(bid, ask, last, close, optPrice, undPrice,
                        impliedVol, delta, gamma, vega, theta,
                        volume, openInterest, greeksReceived);
            }
        }
    }
}
