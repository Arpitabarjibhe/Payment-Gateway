package com.chronos.gateway.repository;

import com.chronos.gateway.model.Direction;
import com.chronos.gateway.model.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, String> {

    List<LedgerEntry> findByTransactionId(String transactionId);

    List<LedgerEntry> findByAccountIdOrderByCreatedAtDesc(String accountId);

    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntry e WHERE e.accountId = :accountId AND e.direction = :direction")
    Long sumAmountByAccountAndDirection(@Param("accountId") String accountId, @Param("direction") Direction direction);
}
