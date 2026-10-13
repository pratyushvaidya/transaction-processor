package com.assessment.transaction_processor.report;

import com.assessment.transaction_processor.entity.Account;
import com.assessment.transaction_processor.entity.LedgerEntry;
import com.assessment.transaction_processor.entity.RejectedRecord;
import com.assessment.transaction_processor.entity.TransactionRecord;
import com.assessment.transaction_processor.enums.FailureReason;
import com.assessment.transaction_processor.enums.PendingReason;
import com.assessment.transaction_processor.enums.TransactionStatus;
import com.assessment.transaction_processor.repository.AccountRepository;
import com.assessment.transaction_processor.repository.DuplicateRequestLogRepository;
import com.assessment.transaction_processor.repository.LedgerEntryRepository;
import com.assessment.transaction_processor.repository.RejectedRecordRepository;
import com.assessment.transaction_processor.repository.TransactionRecordRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

@Service
public class ProcessingReportService {

    private final AccountRepository accountRepository;
    private final TransactionRecordRepository transactionRepository;
    private final DuplicateRequestLogRepository duplicateLogRepository;
    private final RejectedRecordRepository rejectedRecordRepository;
    private final LedgerEntryRepository ledgerRepository;
    private final Clock clock;

    public ProcessingReportService(
            AccountRepository accountRepository,
            TransactionRecordRepository transactionRepository,
            DuplicateRequestLogRepository duplicateLogRepository,
            RejectedRecordRepository rejectedRecordRepository,
            LedgerEntryRepository ledgerRepository,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.duplicateLogRepository = duplicateLogRepository;
        this.rejectedRecordRepository = rejectedRecordRepository;
        this.ledgerRepository = ledgerRepository;
        this.clock = clock;
    }

    public ProcessingReport createReport() {
        Instant now = clock.instant();
        List<Account> accounts = accountRepository.findAll();
        List<TransactionRecord> transactions = transactionRepository.findAll();
        List<LedgerEntry> ledgerEntries = ledgerRepository.findAll();
        List<RejectedRecord> rejected = rejectedRecordRepository.findAll();

        Map<TransactionStatus, Long> statusCounts = new EnumMap<>(TransactionStatus.class);
        for (TransactionStatus status : TransactionStatus.values()) {
            statusCounts.put(status, 0L);
        }
        Map<PendingReason, Long> pendingCounts = new EnumMap<>(PendingReason.class);
        Map<FailureReason, Long> failedCounts = new EnumMap<>(FailureReason.class);
        long retriedTransactions = 0L;
        long totalRetryAttempts = 0L;
        Map<String, List<TransactionRecord>> accountTransactions = new HashMap<>();
        for (TransactionRecord transaction : transactions) {
            statusCounts.compute(transaction.getStatus(), (ignored, count) -> count + 1L);
            if (transaction.getStatus() == TransactionStatus.PENDING && transaction.getPendingReason() != null) {
                pendingCounts.merge(transaction.getPendingReason(), 1L, Long::sum);
            }
            if (transaction.getStatus() == TransactionStatus.FAILED && transaction.getFailureReason() != null) {
                failedCounts.merge(transaction.getFailureReason(), 1L, Long::sum);
            }
            if (transaction.getAttemptCount() > 1) {
                retriedTransactions++;
                totalRetryAttempts += transaction.getAttemptCount() - 1L;
            }
            accountTransactions.computeIfAbsent(transaction.getAccountId(), ignored -> new ArrayList<>())
                    .add(transaction);
        }
        for (RejectedRecord rejectedRecord : rejected) {
            failedCounts.merge(rejectedRecord.getFailureReason(), 1L, Long::sum);
            statusCounts.compute(TransactionStatus.FAILED, (ignored, count) -> count + 1L);
        }
        statusCounts.put(TransactionStatus.DUPLICATE, duplicateLogRepository.count());

        Map<String, BigDecimal> ledgerTotals = ledgerTotals(ledgerEntries);
        List<ProcessingReport.AccountSummary> summaries = accounts.stream()
                .sorted(Comparator.comparing(Account::getAccountId))
                .map(account -> summarize(account, accountTransactions.getOrDefault(account.getAccountId(), List.of()), now))
                .toList();
        boolean integrityMatches = accounts.stream().allMatch(account ->
                ledgerTotals.getOrDefault(account.getAccountId(), BigDecimal.ZERO.setScale(2))
                        .compareTo(account.getBalance()) == 0);

        return new ProcessingReport(
                statusCounts,
                pendingCounts,
                failedCounts,
                statusCounts.get(TransactionStatus.DUPLICATE),
                retriedTransactions,
                totalRetryAttempts,
                summaries,
                rejected.stream().map(RejectedRecord::toView).toList(),
                integrityMatches);
    }

    private static Map<String, BigDecimal> ledgerTotals(List<LedgerEntry> entries) {
        Map<String, BigDecimal> totals = new HashMap<>();
        for (LedgerEntry entry : entries) {
            BigDecimal signedAmount = entry.getType() == com.assessment.transaction_processor.enums.TransactionType.CREDIT
                    ? entry.getAmount()
                    : entry.getAmount().negate();
            totals.merge(entry.getAccountId(), signedAmount, BigDecimal::add);
        }
        return totals;
    }

    private static ProcessingReport.AccountSummary summarize(
            Account account, List<TransactionRecord> transactions, Instant now) {
        List<TransactionRecord> pending = transactions.stream()
                .filter(transaction -> transaction.getStatus() == TransactionStatus.PENDING)
                .toList();
        Set<Long> occupiedSequences = new TreeSet<>();
        long highestSequence = account.getNextExpectedSeq() - 1L;
        for (TransactionRecord transaction : transactions) {
            occupiedSequences.add(transaction.getSequenceNumber());
            highestSequence = Math.max(highestSequence, transaction.getSequenceNumber());
        }

        List<ProcessingReport.SequenceRange> gaps = missingRanges(
                account.getNextExpectedSeq(), highestSequence, occupiedSequences);
        Duration oldestPendingAge = pending.stream()
                .map(transaction -> Duration.between(transaction.getReceivedAt(), now))
                .max(Duration::compareTo)
                .orElse(Duration.ZERO);
        return new ProcessingReport.AccountSummary(
                account.getAccountId(),
                account.getBalance(),
                account.getNextExpectedSeq(),
                pending.size(),
                gaps,
                oldestPendingAge);
    }

    private static List<ProcessingReport.SequenceRange> missingRanges(
            long cursor, long highestSequence, Set<Long> occupiedSequences) {
        List<ProcessingReport.SequenceRange> ranges = new ArrayList<>();
        long expected = cursor;
        for (long occupied : occupiedSequences) {
            if (occupied < cursor || occupied > highestSequence) {
                continue;
            }
            if (occupied > expected) {
                ranges.add(new ProcessingReport.SequenceRange(expected, occupied - 1L));
            }
            if (occupied == Long.MAX_VALUE) {
                return ranges;
            }
            expected = Math.max(expected, occupied + 1L);
        }
        return ranges;
    }
}
