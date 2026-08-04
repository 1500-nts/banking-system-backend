package com.banking.accountservice.controller;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.CreateAccountRequest;
import com.banking.accountservice.entity.Account;
import com.banking.accountservice.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
@Slf4j
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    /*
     * Endpoints, split by who can call them (enforced twice: broad
     * ADMIN-only routes are blocked at the Gateway; per-resource
     * ownership - "is this MY account" - is checked here, since only
     * this service knows which account belongs to which user).
     *
     * 1. create acc         -> ADMIN only        (Gateway-enforced)
     * 2. list all accounts  -> ADMIN only         (Gateway-enforced)
     * 3. get own accounts   -> any authenticated user (/me)
     * 4. get acc by number  -> ADMIN (any) or USER (own only)
     * 5. get balance        -> ADMIN (any) or USER (own only)
     * 6. block acc          -> ADMIN only         (Gateway-enforced)
     * 7/8. deduct/credit    -> internal SAGA calls from transaction-service,
     *                          not routed through the Gateway
     * */

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(
            @Valid @RequestBody CreateAccountRequest request){
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(accountService.createAccount(request));
    }

    // ADMIN ONLY - every account in the system
    @GetMapping
    public ResponseEntity<List<AccountResponse>> getAllAccounts(){
        return ResponseEntity.ok(accountService.getAllAccounts());
    }

    // Any authenticated user - their own account(s) only
    @GetMapping("/me")
    public ResponseEntity<List<AccountResponse>> getMyAccounts(
            @RequestHeader("X-User-Id") String userId){
        return ResponseEntity.ok(accountService.getAccountsForUser(userId));
    }

    @GetMapping("/{accountNumber}")
    public ResponseEntity<AccountResponse> getAccount(
            @PathVariable String accountNumber,
            @RequestHeader("X-User-Id") String requesterId,
            @RequestHeader("X-User-Role") String requesterRole){
        enforceOwnership(accountNumber, requesterId, requesterRole);
        return ResponseEntity.ok(accountService.getAccount(accountNumber));
    }

    @GetMapping("/{accountNumber}/balance")
    public ResponseEntity<BigDecimal> getBalance(
            @PathVariable String accountNumber,
            @RequestHeader("X-User-Id") String requesterId,
            @RequestHeader("X-User-Role") String requesterRole){
        enforceOwnership(accountNumber, requesterId, requesterRole);
        return ResponseEntity.ok(accountService.getBalance(accountNumber));
    }

    // Admins can access any account; a regular user may only access
    // their own - otherwise this returns 403.
    private void enforceOwnership(String accountNumber, String requesterId, String requesterRole){
        if ("ADMIN".equalsIgnoreCase(requesterRole)) {
            return;
        }
        Account account = accountService.getAccountEntity(accountNumber);
        if (!account.getUserId().equals(requesterId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can only access your own account");
        }
    }

    @PutMapping("/{accountNumber}/block")
    public ResponseEntity<String> BlockAccount(
            @PathVariable String accountNumber){
        accountService.blockAccount(accountNumber);
        return ResponseEntity.ok("Account blocked Successfully");
    }

    /*
    * SAGA STEP 1 -> Deduct Balance
    * Called by Transaction Service when transfer is initiated
    * */

    @PutMapping("/{accountNumber}/deduct")
    public ResponseEntity<String> deductBalance(
            @PathVariable String accountNumber,
            @RequestParam BigDecimal amount){
        accountService.deductBalance(accountNumber, amount);
        return ResponseEntity.ok("Balance deduct Successfully");
    }

    /*
    * SAGA STEP 4 -> Compensating transaction endpoint
    * CALLED BY TRANSACTION SERVICE in TWO SCENARIOS
    * 1. Fraud detected -> refund sender (undo step 1)
    * 2. Transaction completed -> Credit receiver
    * */

    @PutMapping("/{accountNumber}/credit")
    public ResponseEntity<String> creditBalance(
            @PathVariable String accountNumber,
            @RequestParam BigDecimal amount){
        accountService.creditBalance(accountNumber, amount);
        return ResponseEntity.ok("BALANCE CREDITED SUCCESSFULLY");
    }
}
