package com.assessment.transaction_processor.service;

import com.assessment.transaction_processor.enums.PendingReason;
import com.assessment.transaction_processor.enums.TransactionStatus;
import com.assessment.transaction_processor.entity.Account;
import com.assessment.transaction_processor.entity.TransactionRecord;
import com.assessment.transaction_processor.repository.AccountRepository;
import com.assessment.transaction_processor.repository.TransactionRecordRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class ProcessingEngine {

    private static final int MAX_STEPS_PER_DRAIN = 10_000;

    private final ProcessingStep processingStep;
    private final TransactionRecordRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final RetryRecorder retryRecorder;
    private final RetryPolicy retryPolicy;

    public ProcessingEngine(
            ProcessingStep processingStep,
            TransactionRecordRepository transactionRepository,
            AccountRepository accountRepository,
            RetryRecorder retryRecorder,
            RetryPolicy retryPolicy) {
        this.processingStep = processingStep;
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.retryRecorder = retryRecorder;
        this.retryPolicy = retryPolicy;
    }

    public void drainAccount(String accountId) {
        for (int iteration = 0; iteration < MAX_STEPS_PER_DRAIN; iteration++) {
            try {
                if (processingStep.executeNext(accountId) == StepResult.BLOCKED) {
                    return;
                }
            } catch (RuntimeException failure) {
                if (!retryPolicy.isTransient(failure)) {
                    throw failure;
                }
                String transactionId;
                if (failure instanceof ProcessingStepException stepFailure) {
                    transactionId = stepFailure.getTransactionId();
                } else {
                    Optional<Account> account = accountRepository.findById(accountId);
                    if (account.isEmpty()) {
                        throw failure;
                    }
                    Optional<TransactionRecord> current = transactionRepository.findByAccountIdAndSequenceNumber(
                            accountId, account.get().getNextExpectedSeq());
                    if (current.isEmpty()) {
                        throw failure;
                    }
                    transactionId = current.get().getTransactionId();
                }
                retryRecorder.recordTransientFailure(accountId, transactionId, failure);
                return;
            }
        }
        throw new IllegalStateException("Processing drain exceeded its iteration safety limit");
    }

    public void drainAll() {
        List<String> accountIds = transactionRepository.findDistinctAccountIdsByStatusAndPendingReason(
                TransactionStatus.PENDING, PendingReason.AWAITING_SEQUENCE);
        accountIds.forEach(this::drainAccount);
    }
}
