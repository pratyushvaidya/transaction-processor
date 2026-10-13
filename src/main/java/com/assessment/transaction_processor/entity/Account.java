package com.assessment.transaction_processor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @Column(name = "account_id", nullable = false, length = 32)
    private String accountId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(name = "next_expected_seq", nullable = false)
    private long nextExpectedSeq;

    @Version
    private long version;

    protected Account() {
    }

    public Account(String accountId) {
        this.accountId = accountId;
        this.balance = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        this.nextExpectedSeq = 1L;
    }

    public String getAccountId() {
        return accountId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public long getNextExpectedSeq() {
        return nextExpectedSeq;
    }

    public long getVersion() {
        return version;
    }

    public void applyCredit(BigDecimal amount) {
        balance = balance.add(amount).setScale(2, RoundingMode.UNNECESSARY);
    }

    public void applyDebit(BigDecimal amount) {
        BigDecimal updatedBalance = balance.subtract(amount).setScale(2, RoundingMode.UNNECESSARY);
        if (updatedBalance.signum() < 0) {
            throw new IllegalArgumentException("Account balance cannot become negative");
        }
        balance = updatedBalance;
    }

    public void advanceSequence() {
        nextExpectedSeq = Math.addExact(nextExpectedSeq, 1L);
    }
}
