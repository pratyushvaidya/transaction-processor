package com.assessment.transaction_processor.service;

import com.assessment.transaction_processor.config.TxnProperties;
import com.assessment.transaction_processor.entity.Account;
import com.assessment.transaction_processor.entity.TransactionRecord;
import com.assessment.transaction_processor.enums.FailureReason;
import com.assessment.transaction_processor.enums.PendingReason;
import com.assessment.transaction_processor.enums.TransactionStatus;
import com.assessment.transaction_processor.observability.TxnEventLogger;
import com.assessment.transaction_processor.repository.AccountRepository;
import com.assessment.transaction_processor.repository.TransactionRecordRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ResilienceJobs {

    private final TransactionRecordRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final ProcessingEngine processingEngine;
    private final TxnProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final TxnEventLogger eventLogger;

    public ResilienceJobs(
            TransactionRecordRepository transactionRepository,
            AccountRepository accountRepository,
            ProcessingEngine processingEngine,
            TxnProperties properties,
            Clock clock,
            PlatformTransactionManager transactionManager,
            TxnEventLogger eventLogger) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.processingEngine = processingEngine;
        this.properties = properties;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.eventLogger = eventLogger;
    }

    public void runDueRetries() {
        List<String> accountIds = transactionRepository.findDueRetryAccountIds(
                TransactionStatus.PENDING, PendingReason.AWAITING_RETRY, clock.instant());
        accountIds.forEach(processingEngine::drainAccount);
    }

    public void expirePendingSequences() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(properties.pending().timeout());
        List<String> transactionIds = transactionRepository.findExpiredPendingIds(
                TransactionStatus.PENDING, PendingReason.AWAITING_SEQUENCE, cutoff);
        transactionIds.forEach(transactionId ->
                transactionTemplate.executeWithoutResult(status -> expireIfStillPending(transactionId, cutoff)));
    }

    public void reclaimStuckProcessing() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(properties.processing().leaseTimeout());
        List<String> transactionIds = transactionRepository.findStuckProcessingIds(
                TransactionStatus.PROCESSING, cutoff);
        transactionIds.forEach(transactionId ->
                transactionTemplate.executeWithoutResult(status -> reclaimIfStillStuck(transactionId, cutoff, now)));
    }

    private void expireIfStillPending(String transactionId, Instant cutoff) {
        Optional<TransactionRecord> candidate = transactionRepository.findById(transactionId);
        if (candidate.isEmpty()) {
            return;
        }
        Account account = accountRepository.findByIdForUpdate(candidate.get().getAccountId()).orElse(null);
        if (account == null) {
            return;
        }
        TransactionRecord transaction = transactionRepository.findById(transactionId).orElse(null);
        if (transaction != null
                && transaction.getStatus() == TransactionStatus.PENDING
                && transaction.getPendingReason() == PendingReason.AWAITING_SEQUENCE
                && transaction.getReceivedAt().isBefore(cutoff)) {
            transaction.markFailed(FailureReason.SEQUENCE_TIMEOUT, clock.instant());
            transaction.setLastErrorMessage(null);
            logAfterCommit("TXN_TIMEOUT", transaction);
        }
    }

    private void reclaimIfStillStuck(String transactionId, Instant cutoff, Instant now) {
        Optional<TransactionRecord> candidate = transactionRepository.findById(transactionId);
        if (candidate.isEmpty()) {
            return;
        }
        Account account = accountRepository.findByIdForUpdate(candidate.get().getAccountId()).orElse(null);
        if (account == null) {
            return;
        }
        TransactionRecord transaction = transactionRepository.findById(transactionId).orElse(null);
        if (transaction != null
                && transaction.getStatus() == TransactionStatus.PROCESSING
                && transaction.getUpdatedAt().isBefore(cutoff)) {
            transaction.markReclaimedForRetry(now);
            logAfterCommit("TXN_LEASE_RECLAIMED", transaction);
        }
    }

    private void logAfterCommit(String event, TransactionRecord transaction) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventLogger.log(event, transaction);
            }
        });
    }
}
