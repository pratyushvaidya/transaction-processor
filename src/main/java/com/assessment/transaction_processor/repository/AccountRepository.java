package com.assessment.transaction_processor.repository;

import com.assessment.transaction_processor.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, String> {
}
