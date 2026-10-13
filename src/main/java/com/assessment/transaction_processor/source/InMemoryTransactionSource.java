package com.assessment.transaction_processor.source;

import com.assessment.transaction_processor.dto.RawTransactionRequest;
import java.util.List;

public class InMemoryTransactionSource implements TransactionSource {

    private final List<RawTransactionRequest> transactions;

    public InMemoryTransactionSource(List<RawTransactionRequest> transactions) {
        this.transactions = List.copyOf(transactions);
    }

    @Override
    public List<RawTransactionRequest> fetchBatch() {
        return transactions;
    }
}
