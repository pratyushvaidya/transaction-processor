package com.assessment.transaction_processor.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class TxnEventLoggerTests {

    @Test
    void given_sensitive_transaction_fields_when_logged_then_masks_account_and_omits_amount() {
        Logger logger = (Logger) LoggerFactory.getLogger(TxnEventLogger.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new TxnEventLogger().log("TXN_RECEIVED", "txn-1", "req-1", "ACC-123456", "PENDING", 1);

            String output = appender.list.get(0).getFormattedMessage();
            assertThat(output).contains("accountMasked=***456");
            assertThat(output).doesNotContain("ACC-123456", "100.00");
            assertThat(appender.list.get(0).getMDCPropertyMap())
                    .containsEntry("accountMasked", "***456")
                    .containsEntry("transactionId", "txn-1");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
