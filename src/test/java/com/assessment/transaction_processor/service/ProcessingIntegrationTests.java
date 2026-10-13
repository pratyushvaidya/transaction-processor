package com.assessment.transaction_processor.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.assessment.transaction_processor.TransactionProcessorApplication;
import com.assessment.transaction_processor.dto.RawTransactionRequest;
import com.assessment.transaction_processor.entity.Account;
import com.assessment.transaction_processor.entity.LedgerEntry;
import com.assessment.transaction_processor.entity.TransactionRecord;
import com.assessment.transaction_processor.enums.FailureReason;
import com.assessment.transaction_processor.enums.IngestionOutcome;
import com.assessment.transaction_processor.enums.PendingReason;
import com.assessment.transaction_processor.enums.TransactionStatus;
import com.assessment.transaction_processor.repository.AccountRepository;
import com.assessment.transaction_processor.repository.DuplicateRequestLogRepository;
import com.assessment.transaction_processor.repository.LedgerEntryRepository;
import com.assessment.transaction_processor.repository.RejectedRecordRepository;
import com.assessment.transaction_processor.repository.TransactionRecordRepository;
import com.assessment.transaction_processor.service.IngestionService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = TransactionProcessorApplication.class)
class ProcessingIntegrationTests {

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private ProcessingEngine processingEngine;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRecordRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerRepository;

    @Autowired
    private DuplicateRequestLogRepository duplicateRepository;

    @Autowired
    private RejectedRecordRepository rejectedRepository;

    @BeforeEach
    void cleanDatabase() {
        ledgerRepository.deleteAll();
        rejectedRepository.deleteAll();
        duplicateRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void given_sequence_two_arrives_first_when_sequence_one_arrives_then_both_process_in_one_drain() {
        assertThat(ingestionService.ingest(request("txn-2", "ACC-201", 2, "DEBIT", "40.00")))
                .isEqualTo(IngestionOutcome.PENDING);
        TransactionRecord buffered = transactionRepository.findById("txn-2").orElseThrow();
        assertThat(buffered.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(buffered.getPendingReason()).isEqualTo(PendingReason.AWAITING_SEQUENCE);

        ingestionService.ingestBatch(List.of(request("txn-1", "ACC-201", 1, "CREDIT", "100.00")));

        assertThat(accountRepository.findById("ACC-201").orElseThrow())
                .satisfies(account -> {
                    assertThat(account.getBalance()).isEqualByComparingTo("60.00");
                    assertThat(account.getNextExpectedSeq()).isEqualTo(3L);
                });
        assertThat(transactionRepository.findAll())
                .extracting(TransactionRecord::getStatus)
                .containsExactlyInAnyOrder(TransactionStatus.PROCESSED, TransactionStatus.PROCESSED);
        assertThat(ledgerRepository.findAll())
                .extracting(LedgerEntry::getTransactionId)
                .containsExactlyInAnyOrder("txn-1", "txn-2");
    }

    @Test
    void given_transactions_in_order_reversed_or_shuffled_when_drained_then_final_ledger_is_identical() {
        List<RawTransactionRequest> transactions = List.of(
                request("txn-1", "ACC-201", 1, "CREDIT", "100.00"),
                request("txn-2", "ACC-201", 2, "DEBIT", "80.00"),
                request("txn-3", "ACC-201", 3, "CREDIT", "10.00"));

        List<String> expectedLedger = null;
        for (List<RawTransactionRequest> ordering : List.of(
                transactions,
                reversed(transactions),
                List.of(transactions.get(1), transactions.get(2), transactions.get(0)))) {
            ingestionService.ingestBatch(ordering);
            Account account = accountRepository.findById("ACC-201").orElseThrow();
            List<String> ledger = ledgerRepository.findAll().stream()
                    .map(entry -> entry.getTransactionId() + ":" + entry.getBalanceAfter().toPlainString())
                    .sorted()
                    .toList();
            assertThat(account.getBalance()).isEqualByComparingTo("30.00");
            assertThat(account.getNextExpectedSeq()).isEqualTo(4L);
            assertThat(ledgerRepository.count()).isEqualTo(3);
            if (expectedLedger == null) {
                expectedLedger = ledger;
            } else {
                assertThat(ledger).isEqualTo(expectedLedger);
            }
            cleanDatabase();
        }
    }

    @Test
    void given_duplicate_after_processing_when_ingested_then_no_extra_ledger_or_state_change() {
        RawTransactionRequest original = request("txn-1", "ACC-201", 1, "CREDIT", "12.00");
        ingestionService.ingestBatch(List.of(original));
        TransactionRecord before = transactionRepository.findById("txn-1").orElseThrow();

        assertThat(ingestionService.ingest(request("txn-1", "ACC-201", 1, "CREDIT", "12.00")))
                .isEqualTo(IngestionOutcome.DUPLICATE);

        assertThat(ledgerRepository.count()).isEqualTo(1);
        assertThat(transactionRepository.findById("txn-1").orElseThrow().getStatus())
                .isEqualTo(before.getStatus());
        assertThat(accountRepository.findById("ACC-201").orElseThrow().getBalance())
                .isEqualByComparingTo("12.00");
    }

    @Test
    void given_duplicate_while_original_is_buffered_when_ingested_then_original_stays_pending() {
        RawTransactionRequest original = request("txn-2", "ACC-201", 2, "CREDIT", "12.00");
        ingestionService.ingest(original);

        assertThat(ingestionService.ingest(request("txn-2", "ACC-201", 2, "DEBIT", "99.00")))
                .isEqualTo(IngestionOutcome.DUPLICATE);

        assertThat(transactionRepository.findById("txn-2").orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.PENDING);
        assertThat(ledgerRepository.count()).isZero();
    }

    @Test
    void given_debit_exceeds_balance_when_drained_then_fails_and_next_sequence_processes() {
        ingestionService.ingestBatch(List.of(
                request("txn-1", "ACC-201", 1, "CREDIT", "10.00"),
                request("txn-2", "ACC-201", 2, "DEBIT", "20.00"),
                request("txn-3", "ACC-201", 3, "CREDIT", "5.00")));

        assertThat(transactionRepository.findById("txn-2").orElseThrow())
                .satisfies(transaction -> {
                    assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.FAILED);
                    assertThat(transaction.getFailureReason()).isEqualTo(FailureReason.INSUFFICIENT_FUNDS);
                });
        assertThat(transactionRepository.findById("txn-3").orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.PROCESSED);
        assertThat(accountRepository.findById("ACC-201").orElseThrow())
                .satisfies(account -> {
                    assertThat(account.getBalance()).isEqualByComparingTo("15.00");
                    assertThat(account.getNextExpectedSeq()).isEqualTo(4L);
                });
        assertThat(ledgerRepository.count()).isEqualTo(2);
    }

    @Test
    void given_validation_failed_record_at_slot_when_drained_then_cursor_skips_it() {
        ingestionService.ingestBatch(List.of(
                new RawTransactionRequest("txn-1", "req-1", "ACC-201", "CREDIT", "0", "USD", "1"),
                request("txn-2", "ACC-201", 2, "CREDIT", "5.00")));

        assertThat(transactionRepository.findById("txn-1").orElseThrow())
                .satisfies(transaction -> {
                    assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.FAILED);
                    assertThat(transaction.getFailureReason()).isEqualTo(FailureReason.VALIDATION_FAILED);
                });
        assertThat(transactionRepository.findById("txn-2").orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.PROCESSED);
        assertThat(accountRepository.findById("ACC-201").orElseThrow().getNextExpectedSeq()).isEqualTo(3L);
        assertThat(ledgerRepository.count()).isEqualTo(1);
    }

    @Test
    void given_interleaved_transactions_for_two_accounts_when_drained_then_balances_are_independent() {
        ingestionService.ingestBatch(List.of(
                request("a-1", "ACC-201", 1, "CREDIT", "10.00"),
                request("b-1", "ACC-202", 1, "CREDIT", "30.00"),
                request("a-2", "ACC-201", 2, "DEBIT", "4.00")));

        assertThat(accountRepository.findById("ACC-201").orElseThrow().getBalance())
                .isEqualByComparingTo("6.00");
        assertThat(accountRepository.findById("ACC-201").orElseThrow().getNextExpectedSeq()).isEqualTo(3L);
        assertThat(accountRepository.findById("ACC-202").orElseThrow().getBalance())
                .isEqualByComparingTo("30.00");
        assertThat(accountRepository.findById("ACC-202").orElseThrow().getNextExpectedSeq()).isEqualTo(2L);
        assertThat(ledgerRepository.count()).isEqualTo(3);
    }

    @Test
    void given_two_threads_ingesting_and_draining_same_account_when_completed_then_each_transaction_is_ledgered_once()
            throws Exception {
        accountRepository.saveAndFlush(new Account("ACC-201"));
        int count = 30;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int parity = 0; parity < 2; parity++) {
                int assignedParity = parity;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrency test start timed out");
                    }
                    for (int sequence = assignedParity + 1; sequence <= count; sequence += 2) {
                        ingestionService.ingest(request(
                                "txn-" + sequence, "ACC-201", sequence, "CREDIT", "1.00"));
                        processingEngine.drainAccount("ACC-201");
                    }
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            futures.forEach(future -> {
                try {
                    future.get(30, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new AssertionError("Concurrent ingestion or draining failed", exception);
                }
            });
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(ledgerRepository.count()).isEqualTo(count);
        assertThat(transactionRepository.count()).isEqualTo(count);
        assertThat(transactionRepository.findAll())
                .allSatisfy(transaction -> assertThat(transaction.getStatus())
                        .isEqualTo(TransactionStatus.PROCESSED));
        assertThat(accountRepository.findById("ACC-201").orElseThrow())
                .satisfies(account -> {
                    assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(count));
                    assertThat(account.getNextExpectedSeq()).isEqualTo(count + 1L);
                });
    }

    private static RawTransactionRequest request(
            String transactionId, String accountId, long sequence, String type, String amount) {
        return new RawTransactionRequest(
                transactionId, "req-" + transactionId, accountId, type, amount, "USD", Long.toString(sequence));
    }

    private static List<RawTransactionRequest> reversed(List<RawTransactionRequest> requests) {
        List<RawTransactionRequest> reversed = new ArrayList<>(requests);
        Collections.reverse(reversed);
        return reversed;
    }
}
