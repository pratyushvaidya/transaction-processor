package com.assessment.transaction_processor.repository;

import com.assessment.transaction_processor.entity.TransactionRecord;
import com.assessment.transaction_processor.enums.PendingReason;
import com.assessment.transaction_processor.enums.TransactionStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRecordRepository extends JpaRepository<TransactionRecord, String> {

    boolean existsByAccountIdAndSequenceNumber(String accountId, long sequenceNumber);

    Optional<TransactionRecord> findByAccountIdAndSequenceNumber(String accountId, long sequenceNumber);

    @Query("select distinct transaction.accountId from TransactionRecord transaction "
            + "where transaction.status = :status and transaction.pendingReason = :pendingReason")
    List<String> findDistinctAccountIdsByStatusAndPendingReason(
            @Param("status") TransactionStatus status,
            @Param("pendingReason") PendingReason pendingReason);
}
