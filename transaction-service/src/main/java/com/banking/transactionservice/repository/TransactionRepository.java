package com.banking.transactionservice.repository;

import com.banking.transactionservice.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction,String> {

    //This method is for creating getTransaction history in TransactionService
    List<Transaction> findBySenderAccountNumberOrderByCreatedAtDesc(String accountNumber);

    // A user's own transaction history (as initiator)
    List<Transaction> findByInitiatedByUserIdOrderByCreatedAtDesc(String userId);

}
