package com.natslash.options_strategy_builder.controller;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.Types;
import com.natslash.options_strategy_builder.entity.Instrument;
import com.natslash.options_strategy_builder.repository.InstrumentRepository;
import com.natslash.options_strategy_builder.service.IbkrClientService;
import com.natslash.options_strategy_builder.service.IbkrHealthCheckService;
import com.natslash.options_strategy_builder.service.OptionsChainService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ChainController.class)
class ChainControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean IbkrClientService       ibkr;
    @MockitoBean OptionsChainService     chainService;
    @MockitoBean InstrumentRepository    instrumentRepository;
    @MockitoBean IbkrHealthCheckService  healthService;

    // ── Helpers ────────────────────────────────────────────────

    /**
     * Build ContractDetails mock BEFORE any when() chains — calling mock() inside
     * thenReturn() arguments causes Mockito UnfinishedStubbing errors.
     */
    private ContractDetails cd(int conId, String symbol, String name,
                                String exchange, String currency,
                                Types.SecType secType, String tradingClass) {
        Contract contract = mock(Contract.class);
        when(contract.conid()).thenReturn(conId);
        when(contract.symbol()).thenReturn(symbol);
        when(contract.exchange()).thenReturn(exchange);
        when(contract.currency()).thenReturn(currency);
        when(contract.secType()).thenReturn(secType);
        when(contract.tradingClass()).thenReturn(tradingClass);
        when(contract.multiplier()).thenReturn("100");

        ContractDetails cd = mock(ContractDetails.class);
        when(cd.contract()).thenReturn(contract);
        when(cd.longName()).thenReturn(name);
        return cd;
    }

    private void noSaved() {
        when(instrumentRepository.findBySymbolAndExchange(any(), any()))
                .thenReturn(Optional.empty());
    }

    // ── searchInstruments ──────────────────────────────────────

    /**
     * IND result comes back — included with secType="IND".
     */
    @Test
    void search_returnsIndResult() throws Exception {
        ContractDetails dax = cd(12345, "DAX", "DAX Index", "EUREX", "EUR", Types.SecType.IND, "DAX");
        when(ibkr.reqContractDetails("DAX", "IND")).thenReturn(CompletableFuture.completedFuture(List.of(dax)));
        when(ibkr.reqContractDetails("DAX", "STK")).thenReturn(CompletableFuture.completedFuture(List.of()));
        noSaved();

        mockMvc.perform(get("/api/instruments/search?symbol=dax"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.length()").value(1))
               .andExpect(jsonPath("$[0].symbol").value("DAX"))
               .andExpect(jsonPath("$[0].secType").value("IND"))
               .andExpect(jsonPath("$[0].exchange").value("EUREX"));
    }

    /**
     * STK result comes back — included with secType="STK".
     */
    @Test
    void search_returnsStkResult() throws Exception {
        ContractDetails aapl = cd(67890, "AAPL", "Apple Inc", "NASDAQ", "USD", Types.SecType.STK, "AAPL");
        when(ibkr.reqContractDetails("AAPL", "IND")).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(ibkr.reqContractDetails("AAPL", "STK")).thenReturn(CompletableFuture.completedFuture(List.of(aapl)));
        noSaved();

        mockMvc.perform(get("/api/instruments/search?symbol=AAPL"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.length()").value(1))
               .andExpect(jsonPath("$[0].symbol").value("AAPL"))
               .andExpect(jsonPath("$[0].secType").value("STK"));
    }

    /**
     * Both IND and STK results with different conIds — both returned.
     */
    @Test
    void search_returnsBothIndAndStk_whenDifferentConIds() throws Exception {
        ContractDetails spxInd = cd(1111, "SPX", "S&P 500 Index", "CBOE", "USD", Types.SecType.IND, "SPX");
        ContractDetails spxStk = cd(2222, "SPX", "S&P 500 ETF", "NYSE", "USD", Types.SecType.STK, "SPX");
        when(ibkr.reqContractDetails("SPX", "IND")).thenReturn(CompletableFuture.completedFuture(List.of(spxInd)));
        when(ibkr.reqContractDetails("SPX", "STK")).thenReturn(CompletableFuture.completedFuture(List.of(spxStk)));
        noSaved();

        mockMvc.perform(get("/api/instruments/search?symbol=SPX"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.length()").value(2));
    }

    /**
     * Same conId returned by both IND and STK queries — deduplicated to one result.
     */
    @Test
    void search_deduplicatesByConId() throws Exception {
        ContractDetails fromInd = cd(99999, "OESX", "Euro Stoxx 50", "EUREX", "EUR", Types.SecType.IND, "OESX");
        ContractDetails fromStk = cd(99999, "OESX", "Euro Stoxx 50", "EUREX", "EUR", Types.SecType.IND, "OESX");
        when(ibkr.reqContractDetails("OESX", "IND")).thenReturn(CompletableFuture.completedFuture(List.of(fromInd)));
        when(ibkr.reqContractDetails("OESX", "STK")).thenReturn(CompletableFuture.completedFuture(List.of(fromStk)));
        noSaved();

        mockMvc.perform(get("/api/instruments/search?symbol=OESX"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.length()").value(1));
    }

    /**
     * IBKR returns a FUT contract — filtered out; empty response to frontend.
     */
    @Test
    void search_filtersOutNonIndStk() throws Exception {
        ContractDetails fut = cd(55555, "ES", "E-Mini S&P 500", "CME", "USD", Types.SecType.FUT, "ES");
        when(ibkr.reqContractDetails("ES", "IND")).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(ibkr.reqContractDetails("ES", "STK")).thenReturn(CompletableFuture.completedFuture(List.of(fut)));
        noSaved();

        mockMvc.perform(get("/api/instruments/search?symbol=ES"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * Instrument already in DB — alreadySaved=true in the response.
     */
    @Test
    void search_marksAlreadySavedInstrument() throws Exception {
        ContractDetails dax = cd(12345, "DAX", "DAX Index", "EUREX", "EUR", Types.SecType.IND, "DAX");
        when(ibkr.reqContractDetails("DAX", "IND")).thenReturn(CompletableFuture.completedFuture(List.of(dax)));
        when(ibkr.reqContractDetails("DAX", "STK")).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(instrumentRepository.findBySymbolAndExchange("DAX", "EUREX"))
                .thenReturn(Optional.of(new Instrument()));

        mockMvc.perform(get("/api/instruments/search?symbol=DAX"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].alreadySaved").value(true));
    }

    /**
     * IBKR throws (gateway not connected) — 503 returned.
     */
    @Test
    void search_returns503WhenIbkrUnavailable() throws Exception {
        when(ibkr.reqContractDetails(any(), any()))
                .thenThrow(new IllegalStateException("IB Gateway not connected"));

        mockMvc.perform(get("/api/instruments/search?symbol=DAX"))
               .andExpect(status().isServiceUnavailable());
    }

    // ── saveInstrument ─────────────────────────────────────────

    /**
     * Re-adding an existing instrument (secType was null) must update secType.
     * This was the root cause of persistent 400s after "re-adding" — the existing
     * path only set active=true and never touched secType.
     */
    @Test
    void saveInstrument_updatesSecType_whenInstrumentAlreadyExists() throws Exception {
        Instrument existing = new Instrument();
        existing.setId(1L);
        existing.setSymbol("OESX");
        existing.setExchange("EUREX");
        existing.setSecType(null); // old data — missing secType

        when(instrumentRepository.findBySymbolAndExchange("OESX", "EUREX"))
                .thenReturn(Optional.of(existing));
        when(instrumentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/instruments")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content("""
                               {"symbol":"OESX","name":"Euro Stoxx 50","exchange":"EUREX",
                                "currency":"EUR","conId":12345,"multiplier":100,
                                "tradingClass":"OESX","secType":"IND","alreadySaved":true}
                               """))
               .andExpect(status().isOk());

        ArgumentCaptor<Instrument> captor = ArgumentCaptor.forClass(Instrument.class);
        verify(instrumentRepository).save(captor.capture());
        assertThat(captor.getValue().getSecType()).isEqualTo("IND");
        assertThat(captor.getValue().getActive()).isTrue();
    }

    // ── removeInstrument ───────────────────────────────────────

    @Test
    void removeInstrument_setsActiveToFalse() throws Exception {
        Instrument inst = new Instrument();
        inst.setId(1L);
        inst.setActive(true);

        when(instrumentRepository.findById(1L)).thenReturn(Optional.of(inst));
        when(instrumentRepository.save(any())).thenReturn(inst);

        mockMvc.perform(delete("/api/instruments/1"))
               .andExpect(status().isOk());

        ArgumentCaptor<Instrument> captor = ArgumentCaptor.forClass(Instrument.class);
        verify(instrumentRepository).save(captor.capture());
        assertThat(captor.getValue().getActive()).isFalse();
    }

    @Test
    void removeInstrument_returns404_whenNotFound() throws Exception {
        when(instrumentRepository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/instruments/99"))
               .andExpect(status().isNotFound());
    }
}
