package com.natslash.options_strategy_builder.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "chain_contracts")
public class ChainContract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "snapshot_id")
    private ChainSnapshot snapshot;

    private String expiry;
    private Integer dte;
    private Double strike;
    private String type;
    private Double bid;
    private Double ask;
    private Double mid;
    private Double close;
    private Double iv;
    private Double delta;
    private Double gamma;
    private Double theta;
    private Double vega;
    private Double premiumEur;
    private Double otmPct;
    private String greeksSource;
}