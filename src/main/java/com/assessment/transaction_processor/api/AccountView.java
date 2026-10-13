package com.assessment.transaction_processor.api;

import com.assessment.transaction_processor.entity.Account;
import java.math.BigDecimal;

public record AccountView(String accountId, BigDecimal balance, long nextExpectedSeq, long version) {

    public static AccountView from(Account account) {
        return new AccountView(
                account.getAccountId(), account.getBalance(), account.getNextExpectedSeq(), account.getVersion());
    }
}
