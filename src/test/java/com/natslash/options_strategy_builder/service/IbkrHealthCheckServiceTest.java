package com.natslash.options_strategy_builder.service;

import com.ib.client.ContractDetails;
import com.natslash.options_strategy_builder.model.IbkrHealthStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IbkrHealthCheckServiceTest {

    @Mock    IbkrClientService    ibkr;
    @Mock    TradingSchedule      tradingSchedule;
    @InjectMocks IbkrHealthCheckService service;

    // ── probe_disconnected ─────────────────────────────────────

    @Test
    void probe_disconnected_returnsUnhealthy_noSocketNoData() {
        when(ibkr.isConnected()).thenReturn(false);
        when(tradingSchedule.isMarketHours()).thenReturn(false);

        IbkrHealthStatus result = service.probe();

        assertThat(result.healthy()).isFalse();
        assertThat(result.socketConnected()).isFalse();
        assertThat(result.dataReceived()).isFalse();
        assertThat(result.dataMode()).isEqualTo("FROZEN");
        verify(ibkr, never()).reqContractDetails(any(), any());
    }

    // ── probe_connected_contractsFound ─────────────────────────

    @Test
    void probe_connected_contractsFound_live_returnsHealthy() {
        when(ibkr.isConnected()).thenReturn(true);
        when(tradingSchedule.isMarketHours()).thenReturn(true);
        when(ibkr.reqContractDetails("ESTX50", "IND"))
                .thenReturn(CompletableFuture.completedFuture(List.of(mock(ContractDetails.class))));

        IbkrHealthStatus result = service.probe();

        assertThat(result.healthy()).isTrue();
        assertThat(result.socketConnected()).isTrue();
        assertThat(result.dataReceived()).isTrue();
        assertThat(result.dataMode()).isEqualTo("LIVE");
        assertThat(result.detail()).isNull();
    }

    // ── probe_connected_contractsFound_frozen ──────────────────

    @Test
    void probe_connected_contractsFound_frozen_returnsHealthy() {
        when(ibkr.isConnected()).thenReturn(true);
        when(tradingSchedule.isMarketHours()).thenReturn(false);
        when(ibkr.reqContractDetails("ESTX50", "IND"))
                .thenReturn(CompletableFuture.completedFuture(List.of(mock(ContractDetails.class))));

        IbkrHealthStatus result = service.probe();

        assertThat(result.healthy()).isTrue();
        assertThat(result.dataMode()).isEqualTo("FROZEN");
    }

    // ── probe_connected_emptyContracts ─────────────────────────

    @Test
    void probe_connected_emptyContracts_returnsUnhealthy() {
        when(ibkr.isConnected()).thenReturn(true);
        when(tradingSchedule.isMarketHours()).thenReturn(false);
        when(ibkr.reqContractDetails("ESTX50", "IND"))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        IbkrHealthStatus result = service.probe();

        assertThat(result.healthy()).isFalse();
        assertThat(result.socketConnected()).isTrue();
        assertThat(result.dataReceived()).isFalse();
        assertThat(result.detail()).contains("ESTX50");
    }

    // ── probe_connected_requestFails ───────────────────────────

    @Test
    void probe_connected_requestFails_returnsUnhealthy() {
        when(ibkr.isConnected()).thenReturn(true);
        when(tradingSchedule.isMarketHours()).thenReturn(false);
        when(ibkr.reqContractDetails("ESTX50", "IND"))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("probe timed out")));

        IbkrHealthStatus result = service.probe();

        assertThat(result.healthy()).isFalse();
        assertThat(result.socketConnected()).isTrue();
        assertThat(result.dataReceived()).isFalse();
        assertThat(result.detail()).contains("probe timed out");
    }

    // ── getHealth_cacheHit_onlyOneProbe ────────────────────────

    @Test
    void getHealth_cacheHit_onlyOneProbe() {
        when(ibkr.isConnected()).thenReturn(true);
        when(tradingSchedule.isMarketHours()).thenReturn(true);
        when(ibkr.reqContractDetails("ESTX50", "IND"))
                .thenReturn(CompletableFuture.completedFuture(List.of(mock(ContractDetails.class))));

        service.getHealth(); // cold — performs probe
        service.getHealth(); // warm — cache hit

        verify(ibkr, times(1)).reqContractDetails("ESTX50", "IND");
    }
}
