package com.natslash.options_strategy_builder.repository;

import com.natslash.options_strategy_builder.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByUserId(Long userId);

    /**
     * Bypasses @Version check — used only by the "broken" experiment to simulate
     * a naive UPDATE that has no optimistic locking protection.
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE user_accounts SET available_balance = :balance, updated_at = :now WHERE id = :id",
           nativeQuery = true)
    void updateBalanceDirectly(@Param("id") Long id,
                               @Param("balance") BigDecimal balance,
                               @Param("now") LocalDateTime now);
}
