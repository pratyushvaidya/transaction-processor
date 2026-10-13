package com.assessment.transaction_processor.service;

import com.assessment.transaction_processor.entity.Account;
import com.assessment.transaction_processor.entity.LedgerEntry;
import com.assessment.transaction_processor.entity.TransactionRecord;
import com.assessment.transaction_processor.enums.FailureReason;
import com.assessment.transaction_processor.enums.TransactionStatus;
import com.assessment.transaction_processor.enums.TransactionType;
import com.assessment.transaction_processor.observability.TxnEventLogger;
import com.assessment.transaction_processor.repository.AccountRepository;
import com.assessment.transaction_processor.repository.LedgerEntryRepository;
import com.assessment.transaction_processor.repository.TransactionRecordRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ProcessingStep {

    private final AccountRepository accountRepository;
    private final TransactionRecordRepository transactionRepository;
    private final LedgerEntryRepository ledgerRepository;
    private final SequenceGate sequenceGate = new SequenceGate();
    private final Clock clock;
    private final FaultInjector faultInjector;
    private final TxnEventLogger eventLogger;

    public ProcessingStep(
            AccountRepository accountRepository,
            TransactionRecordRepository transactionRepository,
            LedgerEntryRepository ledgerRepository,
            Clock clock,
            FaultInjector faultInjector,
            TxnEventLogger eventLogger) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerRepository = ledgerRepository;
        this.clock = clock;
        this.faultInjector = faultInjector;
        this.eventLogger = eventLogger;
    }

    @Transactional
    public StepResult executeNext(String accountId) {
        Optional<Account> lockedAccount = accountRepository.findByIdForUpdate(accountId);
        if (lockedAccount.isEmpty()) {
            return StepResult.BLOCKED;
        }

        Account account = lockedAccount.get();
        long cursor = account.getNextExpectedSeq();
        Optional<TransactionRecord> current =
                transactionRepository.findByAccountIdAndSequenceNumber(accountId, cursor);
        SequenceGate.Decision decision = sequenceGate.decide(cursor, current, clock.instant());

        if (decision == SequenceGate.Decision.WAIT_GAP || decision == SequenceGate.Decision.WAIT_RETRY) {
            return StepResult.BLOCKED;
        }
        if (decision == SequenceGate.Decision.SKIP_TERMINAL) {
            account.advanceSequence();
            return StepResult.PROGRESSED;
        }

        TransactionRecord transaction = current.orElseThrow();
        if (transaction.getSequenceNumber() != account.getNextExpectedSeq()
                || transaction.getStatus() != TransactionStatus.PENDING) {
            return StepResult.BLOCKED;
        }

        try {
            return processCurrent(account, transaction, accountId);
        } catch (RuntimeException failure) {
            throw new ProcessingStepException(transaction.getTransactionId(), failure);
        }
    }

    private StepResult processCurrent(Account account, TransactionRecord transaction, String accountId) {
        Instant processingTime = clock.instant();
        transaction.markProcessing(processingTime);
        if (transaction.getType() == TransactionType.DEBIT
                && transaction.getAmount().compareTo(account.getBalance()) > 0) {
            transaction.incrementAttemptCount();
            transaction.markFailed(FailureReason.INSUFFICIENT_FUNDS, clock.instant());
            account.advanceSequence();
            logAfterCommit("TXN_FAILED", transaction);
            return StepResult.PROGRESSED;
        }

        if (transaction.getType() == TransactionType.CREDIT) {
            account.applyCredit(transaction.getAmount());
        } else {
            account.applyDebit(transaction.getAmount());
        }
        Instant processedAt = clock.instant();
        ledgerRepository.save(new LedgerEntry(
                transaction.getTransactionId(),
                accountId,
                transaction.getType(),
                transaction.getAmount(),
                account.getBalance(),
                processedAt));
        faultInjector.maybeFail(transaction);
        transaction.incrementAttemptCount();
        transaction.markProcessed(processedAt);
        account.advanceSequence();
        logAfterCommit("TXN_PROCESSED", transaction);
        return StepResult.PROGRESSED;
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
