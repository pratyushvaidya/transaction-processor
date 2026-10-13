package com.assessment.transaction_processor.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.assessment.transaction_processor.entity.Account;
import com.assessment.transaction_processor.entity.TransactionRecord;
import com.assessment.transaction_processor.repository.AccountRepository;
import com.assessment.transaction_processor.repository.TransactionRecordRepository;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProcessingEngineTests {

    @Test
    void given_step_throws_transient_exception_when_drained_then_records_failure_and_stops() {
        ProcessingStep step = mock(ProcessingStep.class);
        TransactionRecordRepository transactions = mock(TransactionRecordRepository.class);
        AccountRepository accounts = mock(AccountRepository.class);
        RetryRecorder recorder = mock(RetryRecorder.class);
        ProcessingEngine engine = new ProcessingEngine(
                step,
                transactions,
                accounts,
                recorder,
                new RetryPolicy(Duration.ofSeconds(30), 3));
        Account account = new Account("ACC-201");
        TransactionRecord transaction = mock(TransactionRecord.class);
        when(step.executeNext("ACC-201"))
                .thenThrow(new TransientProcessingException("fail once"));
        when(accounts.findById("ACC-201")).thenReturn(Optional.of(account));
        when(transactions.findByAccountIdAndSequenceNumber("ACC-201", 1L))
                .thenReturn(Optional.of(transaction));
        when(transaction.getTransactionId()).thenReturn("txn-1");

        engine.drainAccount("ACC-201");

        verify(recorder).recordTransientFailure(
                org.mockito.ArgumentMatchers.eq("ACC-201"),
                org.mockito.ArgumentMatchers.eq("txn-1"),
                org.mockito.ArgumentMatchers.any(TransientProcessingException.class));
        verify(step, times(1)).executeNext("ACC-201");
    }
}
