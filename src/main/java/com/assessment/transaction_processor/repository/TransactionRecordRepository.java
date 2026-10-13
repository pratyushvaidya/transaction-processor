package com.assessment.transaction_processor.repository;

import com.assessment.transaction_processor.entity.TransactionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRecordRepository extends JpaRepository<TransactionRecord, String> {

    boolean existsByAccountIdAndSequenceNumber(String accountId, long sequenceNumber);
}
