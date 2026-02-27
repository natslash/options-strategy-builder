package com.natslash.options_strategy_builder.service;

import com.natslash.options_strategy_builder.entity.ChainContract;
import com.natslash.options_strategy_builder.entity.ChainSnapshot;
import com.natslash.options_strategy_builder.model.OptionContract;
import org.springframework.stereotype.Component;

import java.util.List;

/** Maps between {@link ChainContract} JPA entities and {@link OptionContract} domain objects. */
@Component
public class ChainContractMapper {

    public List<OptionContract> toOptionContracts(List<ChainContract> entities, double spot) {
        return entities.stream().map(e -> OptionContract.builder()
                .expiry(e.getExpiry())
                .dte(e.getDte())
                .strike(e.getStrike())
                .type(e.getType())
                .bid(e.getBid())
                .ask(e.getAsk())
                .midPrice(e.getMid())
                .close(e.getClose())
                .iv(e.getIv())
                .delta(e.getDelta())
                .gamma(e.getGamma())
                .theta(e.getTheta())
                .vega(e.getVega())
                .premiumEur(e.getPremiumEur())
                .otmPct(e.getOtmPct())
                .greeksSource(e.getGreeksSource())
                .build()).toList();
    }

    public ChainContract toChainContract(OptionContract oc, ChainSnapshot snapshot) {
        ChainContract e = new ChainContract();
        e.setSnapshot(snapshot);
        e.setExpiry(oc.getExpiry());
        e.setDte(oc.getDte());
        e.setStrike(oc.getStrike());
        e.setType(oc.getType());
        e.setBid(oc.getBid());
        e.setAsk(oc.getAsk());
        e.setMid(oc.getMidPrice());
        e.setClose(oc.getClose());
        e.setIv(oc.getIv());
        e.setDelta(oc.getDelta());
        e.setGamma(oc.getGamma());
        e.setTheta(oc.getTheta());
        e.setVega(oc.getVega());
        e.setPremiumEur(oc.getPremiumEur());
        e.setOtmPct(oc.getOtmPct());
        e.setGreeksSource(oc.getGreeksSource());
        return e;
    }
}
