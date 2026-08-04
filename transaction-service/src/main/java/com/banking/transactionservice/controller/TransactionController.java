package com.banking.transactionservice.controller;

import com.banking.transactionservice.dto.TransactionResponse;
import com.banking.transactionservice.dto.TransferRequest;
import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
@Slf4j
public class TransactionController {

    private final TransactionService transactionService;

    // Transfer money between accounts - the initiator is whoever the
    // Gateway authenticated (X-User-Id), NOT something the client can spoof
    // by putting a different id in the request body.
    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> transfer(
            @Valid @RequestBody TransferRequest request,
            @RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transactionService.transfer(request, userId));
    }

    // ADMIN ONLY - every transaction in the system
    @GetMapping
    public ResponseEntity<List<TransactionResponse>> getAllTransactions() {
        return ResponseEntity.ok(transactionService.getAllTransactions());
    }

    // Any authenticated user - transactions THEY initiated
    @GetMapping("/me")
    public ResponseEntity<List<TransactionResponse>> getMyTransactions(
            @RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(transactionService.getMyTransactions(userId));
    }

    // Get transaction by ID - admin can view any; a user can only view
    // one they initiated themselves.
    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionResponse> getTransaction(
            @PathVariable String transactionId,
            @RequestHeader("X-User-Id") String requesterId,
            @RequestHeader("X-User-Role") String requesterRole) {
        Transaction transaction = transactionService.getTransactionEntity(transactionId);
        enforceOwnership(transaction, requesterId, requesterRole);
        return ResponseEntity.ok(
                transactionService.getTransaction(transactionId));
    }

    // Get transaction history for an arbitrary account - ADMIN ONLY.
    // Regular users should use /me instead, which is already scoped to them.
    @GetMapping("/account/{accountNumber}")
    public ResponseEntity<List<TransactionResponse>> getHistory(
            @PathVariable String accountNumber) {
        return ResponseEntity.ok(
                transactionService.getTransactionHistory(accountNumber));
    }


    @PostMapping("/{transactionId}/verify")
    public ResponseEntity<TransactionResponse> verifyTransaction(
            @PathVariable String transactionId,
            @RequestParam String otp) {
        log.info("OTP verification request — transaction: {}",
                transactionId);
        return ResponseEntity.ok(
                transactionService.verifyOTP(transactionId, otp));
    }

    // Admins can view any transaction; a regular user may only view one
    // they personally initiated - otherwise this returns 403.
    private void enforceOwnership(Transaction transaction, String requesterId, String requesterRole) {
        if ("ADMIN".equalsIgnoreCase(requesterRole)) {
            return;
        }
        if (!transaction.getInitiatedByUserId().equals(requesterId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can only access your own transactions");
        }
    }
}
