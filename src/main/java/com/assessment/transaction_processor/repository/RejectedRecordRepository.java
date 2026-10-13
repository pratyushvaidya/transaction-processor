package com.assessment.transaction_processor.repository;

import com.assessment.transaction_processor.entity.RejectedRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RejectedRecordRepository extends JpaRepository<RejectedRecord, Long> {
}
