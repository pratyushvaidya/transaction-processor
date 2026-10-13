package com.assessment.transaction_processor.report;

import com.assessment.transaction_processor.entity.RejectedRecord.RejectedRecordView;
import com.assessment.transaction_processor.enums.FailureReason;
import com.assessment.transaction_processor.enums.PendingReason;
import com.assessment.transaction_processor.enums.TransactionStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public record ProcessingReport(
        Map<TransactionStatus, Long> countsByStatus,
        Map<PendingReason, Long> pendingByReason,
        Map<FailureReason, Long> failedByReason,
        long duplicateCount,
        long retriedTransactions,
        long totalRetryAttempts,
        List<AccountSummary> accounts,
        List<RejectedRecordView> rejectedRecords,
        boolean ledgerBalancesMatch) {

    public ProcessingReport {
        countsByStatus = Map.copyOf(countsByStatus);
        pendingByReason = Map.copyOf(pendingByReason);
        failedByReason = Map.copyOf(failedByReason);
        accounts = List.copyOf(accounts);
        rejectedRecords = List.copyOf(rejectedRecords);
    }

    public long activeCount() {
        return countsByStatus.getOrDefault(TransactionStatus.PENDING, 0L)
                + countsByStatus.getOrDefault(TransactionStatus.PROCESSING, 0L);
    }

    public record AccountSummary(
            String accountId,
            BigDecimal balance,
            long nextExpectedSeq,
            long pendingCount,
            List<SequenceRange> gaps,
            Duration oldestPendingAge) {

        public AccountSummary {
            gaps = List.copyOf(gaps);
        }
    }

    public record SequenceRange(long from, long to) {
    }
}
