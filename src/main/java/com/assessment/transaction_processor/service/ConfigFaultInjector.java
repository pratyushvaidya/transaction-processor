package com.assessment.transaction_processor.service;

import com.assessment.transaction_processor.config.TxnProperties;
import com.assessment.transaction_processor.entity.TransactionRecord;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class ConfigFaultInjector implements FaultInjector {

    private final TxnProperties.FaultInjection settings;

    public ConfigFaultInjector(TxnProperties properties) {
        this.settings = properties.faultInjection();
    }

    @Override
    public void maybeFail(TransactionRecord transaction) {
        if (!settings.enabled()) {
            return;
        }
        int configuredFailures = settings.failures().getOrDefault(
                transaction.getTransactionId(),
                settings.failures().getOrDefault(
                        transaction.getTransactionId().toUpperCase(Locale.ROOT), 0));
        if (transaction.getAttemptCount() < configuredFailures) {
            throw new TransientProcessingException("Configured transient processing failure");
        }
    }
}
