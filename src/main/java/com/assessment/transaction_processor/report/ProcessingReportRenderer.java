package com.assessment.transaction_processor.report;

import com.assessment.transaction_processor.enums.TransactionStatus;
import java.util.StringJoiner;
import org.springframework.stereotype.Component;

@Component
public class ProcessingReportRenderer {

    public String render(ProcessingReport report) {
        StringBuilder text = new StringBuilder();
        text.append("Processing Report\n");
        text.append("=================\n");
        text.append("Status\tCount\n");
        for (TransactionStatus status : TransactionStatus.values()) {
            text.append(status)
                    .append('\t')
                    .append(report.countsByStatus().getOrDefault(status, 0L))
                    .append('\n');
        }
        text.append("\nAccounts\n");
        text.append("Account\tBalance\tCursor\tPending\tGaps\tOldest pending\n");
        for (ProcessingReport.AccountSummary account : report.accounts()) {
            StringJoiner gaps = new StringJoiner(",");
            account.gaps().forEach(gap -> gaps.add(gap.from() + "-" + gap.to()));
            text.append(account.accountId()).append('\t')
                    .append(account.balance().toPlainString()).append('\t')
                    .append(account.nextExpectedSeq()).append('\t')
                    .append(account.pendingCount()).append('\t')
                    .append(gaps).append('\t')
                    .append(account.oldestPendingAge()).append('\n');
        }
        text.append("\nPending by reason: ").append(report.pendingByReason());
        text.append("\nFailed by reason: ").append(report.failedByReason());
        text.append("\nDuplicate requests: ").append(report.duplicateCount());
        text.append("\nRetried transactions: ").append(report.retriedTransactions());
        text.append("\nTotal retry attempts: ").append(report.totalRetryAttempts());
        text.append("\nLedger balances match: ").append(report.ledgerBalancesMatch());
        text.append("\nRejected records: ").append(report.rejectedRecords().size()).append('\n');
        return text.toString();
    }
}
