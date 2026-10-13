package com.assessment.transaction_processor.service;

import com.assessment.transaction_processor.entity.Account;
import com.assessment.transaction_processor.entity.TransactionRecord;
import com.assessment.transaction_processor.enums.FailureReason;
import com.assessment.transaction_processor.enums.TransactionStatus;
import com.assessment.transaction_processor.repository.AccountRepository;
import com.assessment.transaction_processor.repository.TransactionRecordRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RetryRecorder {

    private final AccountRepository accountRepository;
    private final TransactionRecordRepository transactionRepository;
    private final RetryPolicy retryPolicy;
    private final Clock clock;

    public RetryRecorder(
            AccountRepository accountRepository,
            TransactionRecordRepository transactionRepository,
            RetryPolicy retryPolicy,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
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
    }

    private static String safeFailureClassName(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }
}
