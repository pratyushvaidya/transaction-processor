package com.assessment.transaction_processor.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.assessment.transaction_processor.TransactionProcessorApplication;
import com.assessment.transaction_processor.dto.RawTransactionRequest;
import com.assessment.transaction_processor.entity.Account;
import com.assessment.transaction_processor.entity.LedgerEntry;
import com.assessment.transaction_processor.entity.TransactionRecord;
import com.assessment.transaction_processor.enums.FailureReason;
import com.assessment.transaction_processor.enums.TransactionStatus;
import com.assessment.transaction_processor.enums.TransactionType;
import com.assessment.transaction_processor.repository.AccountRepository;
import com.assessment.transaction_processor.repository.DuplicateRequestLogRepository;
import com.assessment.transaction_processor.repository.LedgerEntryRepository;
import com.assessment.transaction_processor.repository.RejectedRecordRepository;
import com.assessment.transaction_processor.repository.TransactionRecordRepository;
import com.assessment.transaction_processor.service.IngestionService;
import com.assessment.transaction_processor.support.MutableClock;
import com.assessment.transaction_processor.support.MutableClockConfiguration;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(classes = TransactionProcessorApplication.class)
@Import(MutableClockConfiguration.class)
class ProcessingReportServiceTests {

    @Autowired
    private ProcessingReportService reportService;

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private MutableClock clock;

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
    void cleanDatabaseAndSetClock() {
        clock.setInstant(Instant.parse("2026-01-01T00:00:00Z"));
        ledgerRepository.deleteAll();
        duplicateRepository.deleteAll();
        rejectedRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void given_buffered_slots_and_ledger_mismatch_when_reported_then_calculates_ranges_counts_and_integrity() {
        ingestionService.ingest(request("txn-3", 3));
        clock.advance(Duration.ofSeconds(60));
        ingestionService.ingest(request("txn-5", 5));
        ingestionService.ingest(new RawTransactionRequest(
                "txn-invalid", "req-invalid", "ACC-REPORT", "CREDIT", "0", "USD", "6"));
        ingestionService.ingest(new RawTransactionRequest(
                "txn-malformed", "req-malformed", null, "CREDIT", "1", "USD", "1"));
        TransactionRecord retried = transactionRepository.findById("txn-3").orElseThrow();
        retried.incrementAttemptCount();
        retried.incrementAttemptCount();
        retried.incrementAttemptCount();
        transactionRepository.saveAndFlush(retried);

        ProcessingReport report = reportService.createReport();

        assertThat(report.pendingByReason()).containsEntry(
                com.assessment.transaction_processor.enums.PendingReason.AWAITING_SEQUENCE, 2L);
        assertThat(report.failedByReason())
                .containsEntry(FailureReason.VALIDATION_FAILED, 1L)
                .containsEntry(FailureReason.MALFORMED, 1L);
        assertThat(report.countsByStatus()).containsEntry(TransactionStatus.FAILED, 2L);
        assertThat(report.retriedTransactions()).isEqualTo(1);
        assertThat(report.totalRetryAttempts()).isEqualTo(2);
        assertThat(report.rejectedRecords()).hasSize(1);
        assertThat(report.rejectedRecords().get(0).safeMessage())
                .isEqualTo("Required transaction identifiers are missing or invalid");
        ProcessingReport.AccountSummary account = report.accounts().get(0);
        assertThat(account.gaps()).containsExactly(
                new ProcessingReport.SequenceRange(1, 2),
                new ProcessingReport.SequenceRange(4, 4));
        assertThat(account.oldestPendingAge()).isEqualTo(Duration.ofSeconds(60));
        assertThat(report.ledgerBalancesMatch()).isTrue();
    }

    @Test
    void given_ledger_effect_does_not_match_account_balance_when_reported_then_integrity_fails() {
        accountRepository.saveAndFlush(new Account("ACC-REPORT"));
        ledgerRepository.saveAndFlush(new LedgerEntry(
                "ledger-only",
                "ACC-REPORT",
                TransactionType.CREDIT,
                new BigDecimal("10.00"),
                new BigDecimal("10.00"),
                clock.instant()));

        assertThat(reportService.createReport().ledgerBalancesMatch()).isFalse();
    }

    private static RawTransactionRequest request(String id, long sequence) {
        return new RawTransactionRequest(id, "req-" + id, "ACC-REPORT", "CREDIT", "1.00", "USD", "" + sequence);
    }
}
