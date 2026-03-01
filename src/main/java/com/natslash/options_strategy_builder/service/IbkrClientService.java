package com.natslash.options_strategy_builder.service;

import com.ib.client.*;
import com.natslash.options_strategy_builder.config.IbkrProperties;
import com.natslash.options_strategy_builder.model.ChainParams;
import com.natslash.options_strategy_builder.model.HistoricalBar;
import com.natslash.options_strategy_builder.model.TickData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.List;
import java.util.concurrent.*;

/**
 * Manages the IBKR socket connection lifecycle.
 * All request logic (accumulator management, future chaining) lives in IbkrDispatcher.
 */
@Slf4j
@Service
public class IbkrClientService {

    private final IbkrProperties props;
    private final IbkrDispatcher dispatcher;
    private EClientSocket client;
    private EReaderSignal signal;

    public IbkrClientService(IbkrProperties props, IbkrDispatcher dispatcher) {
        this.props      = props;
        this.dispatcher = dispatcher;
    }

    // ═══════════════════════════════════════════════════════════
    // Connection
    // ═══════════════════════════════════════════════════════════

    public void connect() throws InterruptedException {
        dispatcher.prepareConnect();
        signal = new EJavaSignal();
        client = new EClientSocket(dispatcher, signal);
        client.eConnect(props.getHost(), props.getPort(), props.getClientId());

        // Give dispatcher the connected client so it can send requests
        dispatcher.setClient(client);

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

        try {
            dispatcher.connectFuture.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Timed out waiting for IBKR connection");
        } catch (ExecutionException e) {
            throw new RuntimeException("IBKR connection failed", e.getCause());
        }
        log.info("Connected to IB Gateway {}:{}", props.getHost(), props.getPort());
    }

    @PostConstruct
    public void init() {
        try {
            connect();
        } catch (Exception e) {
            log.warn("IB Gateway not available at {}:{}: {} — start IBGW and restart, or use cached data",
                    props.getHost(), props.getPort(), e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 15_000)
    public void reconnectIfDisconnected() {
        if (isConnected()) return;
        log.info("IBGW not connected — attempting reconnect...");
        try {
            connect();
            log.info("Reconnected to IBGW successfully");
        } catch (Exception e) {
            log.debug("Reconnect attempt failed: {}", e.getMessage());
        }
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

    // ═══════════════════════════════════════════════════════════
    // Request delegation — implementation lives in IbkrDispatcher
    // ═══════════════════════════════════════════════════════════

    public void reqMarketDataType(int type) {
        if (!isConnected()) return;
        dispatcher.reqMarketDataType(type);
    }

    public CompletableFuture<List<ContractDetails>> reqContractDetails(String symbol, String secType) {
        requireConnected();
        return dispatcher.reqContractDetails(symbol, secType);
    }

    public CompletableFuture<ChainParams> reqChainParams(String symbol, String secType, int conId) {
        requireConnected();
        return dispatcher.reqChainParams(symbol, secType, conId);
    }

    public CompletableFuture<TickData> reqMktData(Contract contract, int timeoutMs) {
        if (!isConnected()) return CompletableFuture.completedFuture(TickData.EMPTY);
        return dispatcher.reqMktData(contract, timeoutMs);
    }

    public CompletableFuture<List<HistoricalBar>> reqHistoricalData(
            Contract contract, String duration, String barSize, String whatToShow) {
        if (!isConnected()) return CompletableFuture.completedFuture(List.of());
        return dispatcher.reqHistoricalData(contract, duration, barSize, whatToShow);
    }

    private void requireConnected() {
        if (!isConnected())
            throw new IllegalStateException(
                    "IB Gateway not connected — start IBGW at " + props.getHost() + ":" + props.getPort());
    }
}
