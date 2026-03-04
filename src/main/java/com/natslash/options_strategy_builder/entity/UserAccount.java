package com.natslash.options_strategy_builder.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "user_accounts")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal totalBalance;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal availableBalance;

    private LocalDateTime updatedAt = LocalDateTime.now();
}