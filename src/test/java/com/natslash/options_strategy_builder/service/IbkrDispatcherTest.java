package com.natslash.options_strategy_builder.service;

import com.ib.client.Contract;
import com.ib.client.EClientSocket;
import com.natslash.options_strategy_builder.model.TickData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IbkrDispatcherTest {

    IbkrDispatcher dispatcher;
    EClientSocket  mockClient;
    Contract       mockContract;

    @BeforeEach
    void setUp() {
        mockClient   = mock(EClientSocket.class);
        mockContract = mock(Contract.class);
        dispatcher   = new IbkrDispatcher();
        dispatcher.setClient(mockClient);
    }

    // ── Snapshot wire format ────────────────────────────────────

    /**
     * Frozen streaming mode: snapshot=false, genericTickList="101" (Option PV Dividend
     * forces IBKR to run the internal Greeks model even when the market is closed).
     */
    @Test
    void reqMktData_uses_streaming_with_101_genericTick() {
        dispatcher.reqMktData(mockContract, 5000);

        verify(mockClient).reqMktData(
                anyInt(), eq(mockContract), eq("101"),
                eq(false), eq(false), any());
    }

    // ── Early completion via tickOptionComputation ─────────────

    /**
     * Field 13 = MODEL_OPTION. Full window elapses then Greeks are present in result.
     * Uses short timeout so test completes quickly.
     */
    @Test
    void greeksReceived_true_forModelField_13() {
        int reqId = dispatcher.reqIdCounter.get();
        CompletableFuture<TickData> future = dispatcher.reqMktData(mockContract, 50);

        dispatcher.tickOptionComputation(reqId, 13, 0,
                0.20, 0.45, 2.50, 0, 0.02, 0.01, -0.05, 5000.0);

        TickData result = future.join();
        assertThat(result.greeksReceived()).isTrue();
        assertThat(result.delta()).isEqualTo(0.45);
    }

    /**
     * Field 10 = BID_OPTION — arrives in frozen snapshots when MODEL is unavailable off-hours.
     */
    @Test
    void greeksReceived_true_forBidOptionField_10() {
        int reqId = dispatcher.reqIdCounter.get();
        CompletableFuture<TickData> future = dispatcher.reqMktData(mockContract, 50);

        dispatcher.tickOptionComputation(reqId, 10, 0,
                0.19, 0.42, 2.30, 0, 0.02, 0.01, -0.04, 5000.0);

        TickData result = future.join();
        assertThat(result.greeksReceived()).isTrue();
        assertThat(result.delta()).isEqualTo(0.42);
    }

    /**
     * Field 11 = ASK_OPTION — same off-hours pattern as field 10.
     */
    @Test
    void greeksReceived_true_forAskOptionField_11() {
        int reqId = dispatcher.reqIdCounter.get();
        CompletableFuture<TickData> future = dispatcher.reqMktData(mockContract, 50);

        dispatcher.tickOptionComputation(reqId, 11, 0,
                0.18, 0.43, 2.60, 0, 0.02, 0.01, -0.05, 5000.0);

        assertThat(future.join().greeksReceived()).isTrue();
    }

    /**
     * Full window collects both field 10 and field 13. Field 13 (MODEL) arrives last
     * and overwrites field 10 values — model wins when both are present.
     */
    @Test
    void field13_overwrites_field10_within_window() {
        int reqId = dispatcher.reqIdCounter.get();
        CompletableFuture<TickData> future = dispatcher.reqMktData(mockContract, 50);

        dispatcher.tickOptionComputation(reqId, 10, 0,
                0.19, 0.40, 2.50, 0, 0.02, 0.01, -0.05, 5000.0); // bid-based delta=0.40
        dispatcher.tickOptionComputation(reqId, 13, 0,
                0.20, 0.45, 2.55, 0, 0.02, 0.01, -0.05, 5000.0); // model delta=0.45

        TickData result = future.join();
        assertThat(result.delta()).isEqualTo(0.45); // model wins
        assertThat(result.greeksReceived()).isTrue();
    }

    /**
     * No tickOptionComputation — greeksReceived stays false. Future completes via timeout.
     */
    @Test
    void greeksReceived_false_whenNoComputationArrives() {
        CompletableFuture<TickData> future = dispatcher.reqMktData(mockContract, 50);

        assertThat(future.join().greeksReceived()).isFalse();
    }
}
