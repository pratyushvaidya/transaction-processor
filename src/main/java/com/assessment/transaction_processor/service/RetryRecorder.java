package com.assessment.transaction_processor.service;

import com.assessment.transaction_processor.entity.Account;
import com.assessment.transaction_processor.entity.TransactionRecord;
import com.assessment.transaction_processor.enums.FailureReason;
import com.assessment.transaction_processor.enums.TransactionStatus;
import com.assessment.transaction_processor.observability.TxnEventLogger;
import com.assessment.transaction_processor.repository.AccountRepository;
import com.assessment.transaction_processor.repository.TransactionRecordRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class RetryRecorder {

    private final AccountRepository accountRepository;
    private final TransactionRecordRepository transactionRepository;
    private final RetryPolicy retryPolicy;
    private final Clock clock;
    private final TxnEventLogger eventLogger;

    public RetryRecorder(
            AccountRepository accountRepository,
            TransactionRecordRepository transactionRepository,
            RetryPolicy retryPolicy,
            Clock clock,
            TxnEventLogger eventLogger) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
        this.eventLogger = eventLogger;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordTransientFailure(String accountId, String transactionId, Throwable failure) {
        Optional<Account> locked = accountRepository.findByIdForUpdate(accountId);
        if (locked.isEmpty()) {
            return;
        }
        Optional<TransactionRecord> candidate = transactionRepository.findById(transactionId);
        if (candidate.isEmpty()) {
            return;
        }

        Account account = locked.get();
        TransactionRecord transaction = candidate.get();
        if (transaction.getSequenceNumber() != account.getNextExpectedSeq()
                || transaction.getStatus() != TransactionStatus.PENDING) {
            return;
        }

        transaction.incrementAttemptCount();
        Instant now = clock.instant();
        if (retryPolicy.isExhausted(transaction.getAttemptCount())) {
            transaction.markFailed(FailureReason.RETRIES_EXHAUSTED, now);
            transaction.setLastErrorMessage(safeFailureClassName(failure));
            account.advanceSequence();
        } else {
            transaction.markAwaitingRetry(
                    retryPolicy.nextAttemptAt(now), now, safeFailureClassName(failure));
        }
        String event = transaction.getStatus() == TransactionStatus.FAILED
                ? "TXN_RETRY_EXHAUSTED"
                : "TXN_RETRY_SCHEDULED";
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventLogger.log(event, transaction);
            }
        });
    }

    private static String safeFailureClassName(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }
}
