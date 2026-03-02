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

    // ── Wire format ─────────────────────────────────────────────

    /**
     * Option market data: streaming mode, snapshot=false,
     * genericTickList="100,101,106" (106 = Option Implied Volatility triggers IBKR
     * server-side Black-Scholes → emits field 13 live, field 83 delayed).
     */
    @Test
    void reqMktData_uses_streaming_with_model_greek_ticks() {
        dispatcher.reqMktData(mockContract, 5000);

        verify(mockClient).reqMktData(
                anyInt(), eq(mockContract), eq("100,101,106"),
                eq(false), eq(false), any());
    }

    /**
     * Underlying price fetch: streaming mode, snapshot=false,
     * genericTickList="" — option-specific generic ticks are unnecessary
     * and would cause IBKR error 321 if combined with snapshot=true.
     */
    @Test
    void reqUnderlyingPrice_sends_no_generic_ticks() {
        dispatcher.reqUnderlyingPrice(mockContract, 5000);

        verify(mockClient).reqMktData(
                anyInt(), eq(mockContract), eq(""),
                eq(false), eq(false), any());
    }

    // ── Greeks via tickOptionComputation ────────────────────────

    /**
     * Field 13 = MODEL_OPTION (live hours). Future completes via timeout;
     * greeksReceived must be true.
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
     * Field 10 = BID_OPTION — arrives when MODEL is unavailable off-hours.
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
     * Field 83 = DELAYED_MODEL_OPTION. Used when IBKR operates in MDT=3/4 (delayed) mode.
     * Same streaming mode as live hours — completes via timeout, not tickSnapshotEnd.
     */
    @Test
    void delayed_model_field83_greeksReceived_true() {
        int reqId = dispatcher.reqIdCounter.get();
        CompletableFuture<TickData> future = dispatcher.reqMktData(mockContract, 50);

        dispatcher.tickOptionComputation(reqId, 83, 0,
                0.22, 0.38, 2.40, 0, 0.015, 0.008, -0.04, 4900.0);
        dispatcher.tickSnapshotEnd(reqId); // IBKR quirk — must be ignored in streaming mode

        TickData result = future.join(); // completes via 50ms timeout
        assertThat(result.greeksReceived()).isTrue();
        assertThat(result.delta()).isEqualTo(0.38);
        assertThat(result.impliedVol()).isEqualTo(0.22);
    }

    /**
     * Field 13 (MODEL) overwrites field 10 (BID_OPTION) when both arrive in the same window.
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

    // ── tickSnapshotEnd (streaming mode) ────────────────────────

    /**
     * tickSnapshotEnd is an IBKR quirk that fires before tickOptionComputation in streaming mode.
     * It must be ignored so Greeks arriving after it are still captured in the timeout window.
     */
    @Test
    void tickSnapshotEnd_ignored_in_streaming_mode_greeks_still_captured() {
        int reqId = dispatcher.reqIdCounter.get();
        CompletableFuture<TickData> streamFuture = dispatcher.reqMktData(mockContract, 200);

        // IBKR quirk: tickSnapshotEnd fires before tickOptionComputation
        dispatcher.tickSnapshotEnd(reqId);
        assertThat(streamFuture).isNotDone(); // must still be running

        // Greeks arrive after the premature tickSnapshotEnd
        dispatcher.tickOptionComputation(reqId, 13, 0,
                0.20, 0.45, 2.50, 0, 0.02, 0.01, -0.05, 5000.0);

        TickData result = streamFuture.join(); // completes via 200ms timeout
        assertThat(result.greeksReceived()).isTrue();
        assertThat(result.delta()).isEqualTo(0.45);
    }

    // ── Early completion ─────────────────────────────────────────

    /**
     * reqUnderlyingPrice sets expectGreeks=false. tryCompleteEarly fires as soon as
     * a last/close price arrives — the future must complete before the 5-second timeout.
     */
    @Test
    void reqUnderlyingPrice_completes_early_on_price_arrival() {
        int reqId = dispatcher.reqIdCounter.get();
        CompletableFuture<TickData> future = dispatcher.reqUnderlyingPrice(mockContract, 5000);

        dispatcher.tickPrice(reqId, 4, 6200.0, null); // field 4 = LAST

        assertThat(future).isDone();
        assertThat(future.join().last()).isEqualTo(6200.0);
    }

    /**
     * reqMktData sets expectGreeks=true. tryCompleteEarly requires BOTH greeksReceived AND
     * at least one price field. The future must stay pending after Greeks alone, then complete
     * immediately once a price tick also arrives — without waiting for the 5-second timeout.
     */
    @Test
    void reqMktData_completes_early_when_price_and_greeks_received() {
        int reqId = dispatcher.reqIdCounter.get();
        CompletableFuture<TickData> future = dispatcher.reqMktData(mockContract, 5000);

        // Greeks arrive first — price still missing, must not complete yet
        dispatcher.tickOptionComputation(reqId, 13, 0,
                0.20, 0.45, 2.50, 0, 0.02, 0.01, -0.05, 5000.0);
        assertThat(future).isNotDone();

        // Price arrives — both conditions met → early completion
        dispatcher.tickPrice(reqId, 4, 2.48, null); // field 4 = LAST

        assertThat(future).isDone();
        assertThat(future.join().delta()).isEqualTo(0.45);
    }
}
