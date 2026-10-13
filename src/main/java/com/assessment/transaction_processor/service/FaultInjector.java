package com.assessment.transaction_processor.service;

import com.assessment.transaction_processor.entity.TransactionRecord;

public interface FaultInjector {

    void maybeFail(TransactionRecord transaction);
}
