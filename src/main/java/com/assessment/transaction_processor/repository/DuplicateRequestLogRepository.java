package com.assessment.transaction_processor.repository;

import com.assessment.transaction_processor.entity.DuplicateRequestLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DuplicateRequestLogRepository extends JpaRepository<DuplicateRequestLog, Long> {
}
