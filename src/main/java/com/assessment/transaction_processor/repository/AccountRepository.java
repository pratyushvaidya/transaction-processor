package com.assessment.transaction_processor.repository;

import com.assessment.transaction_processor.entity.Account;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from Account account where account.accountId = :accountId")
    Optional<Account> findByIdForUpdate(@Param("accountId") String accountId);
}
