package com.banking.accountservice.service;
/* Every Money movement happening hee */

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.CreateAccountRequest;
import com.banking.accountservice.entity.Account;
import com.banking.accountservice.entity.AccountStatus;
import com.banking.accountservice.entity.AccountType;
import com.banking.accountservice.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.SecureRandom;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountService {
    private final AccountRepository accountRepository;
    private static SecureRandom secureRandom = new SecureRandom();

    public AccountResponse createAccount(CreateAccountRequest request){
        log.info("Creating account for : {}", request.getEmail());

        if(accountRepository.existsByEmail(request.getEmail())){
            throw new RuntimeException("Account already exists for email: " + request.getEmail());
        }

        Account account = new Account(); // we BUILDER aslo
        account.setUserId(request.getUserId());
        account.setAccountHolderName(request.getAccountHolderName());
        account.setEmail(request.getEmail());
        account.setPhone(request.getPhone());
        account.setAccountType(request.getAccountType());
        account.setStatus(AccountStatus.ACTIVE);
        account.setBalance(request.getInitialDeposit());
        //HERE WE CAN PUT ACCOUNT NUMBER -> WE NEED TO GENERATE THE ACC NUMBER
        /*Account Number Specification ->
        1. Acc. No. should be Unique
        2. Acc. No. should be 12 digits
        So  for that we need to write separate logic
        * */
        account.setAccountNumber(generateAccountNumber());

        /*
        Daily Trans limit depends on Type of Account
        eg. SAVINGS ACC limit -> $ 100,000
        and CURRENT / FD -> $500,000
        * */
        // HERE WE ARE WRITING LAMBDA EXPRESSION
        account.setDailyTransactionLimit(
                request.getAccountType() == AccountType.SAVINGS
                        ? new BigDecimal("100000")
                        :new BigDecimal("500000")
        );

        Account savedAccount = accountRepository.save(account);
        log.info("Account created: {}", savedAccount.getAccountNumber());

        return mapToResponse(savedAccount);
    }


    // create getAccount method
    public AccountResponse getAccount(String accountNumber){
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Account not found"));

        return mapToResponse(account);
    }

    // Raw entity lookup - used by the controller to check ownership
    // (account.getUserId() vs the requester's X-User-Id) before deciding
    // whether to allow the request through.
    public Account getAccountEntity(String accountNumber){
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Account not found"));
    }

    // ADMIN ONLY - list every account in the system
    public java.util.List<AccountResponse> getAllAccounts(){
        return accountRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .collect(java.util.stream.Collectors.toList());
    }

    // Accounts belonging to a specific user (used for GET /me)
    public java.util.List<AccountResponse> getAccountsForUser(String userId){
        return accountRepository.findByUserId(userId)
                .stream()
                .map(this::mapToResponse)
                .collect(java.util.stream.Collectors.toList());
    }

    //create getBalance method
    public BigDecimal getBalance(String accountNumber){
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Account not found"));

        return account.getBalance();
    }

    //create blockAccount method -> called by fraud detection service via kafka
    public void blockAccount(String accountNumber){
        log.info("Blocking Account: {}", accountNumber);
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Account not found"));

        account.setStatus(AccountStatus.BLOCKED);
        accountRepository.save(account);
        log.info("Account Blocked : {}", accountNumber);
    }

    // Create method for deductBalance
    // Called by Transaction service
    public void deductBalance(String accountNumber, BigDecimal amount){
        log.info("I am deducting balance {} from account: {}", amount, accountNumber);
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Account not found"));

        if(account.getStatus() != AccountStatus.ACTIVE){
            throw new RuntimeException("Account is not active" + accountNumber);
        }

        if(account.getBalance().compareTo(amount) < 0){
            throw new RuntimeException("Insufficient fund for account "+ accountNumber);
        }

        account.setBalance(account.getBalance().subtract(amount));
        accountRepository.save(account);

        log.info("Balance updated. New Balance: {}", account.getBalance());
    }

    // create method for Credit Balance
    // Called by Transaction Service via kafka
    public void creditBalance(String accountNumber, BigDecimal amount){
        log.info("Crediting {} to account: {}", amount, accountNumber);
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Account not found"));

        account.setBalance(account.getBalance().add(amount));
        accountRepository.save(account);

        log.info("Balance Credited. New Balance: {}", account.getBalance());
    }

    //Generate unique 12 digits account number
    private String generateAccountNumber(){
        String accountNumber;

        do{
            long number = secureRandom.nextLong(1_000_000_000_000L); //here we set out upper limit 13 digits we can generate less than this number up to 12 digits only can not 13 digits
            // but this generate 0 t0 12 digits no. but we only need of 12 digits number how can do this
            // if number = 12345 then add zeros it becomes 000000012345

            accountNumber = String.format("%012d", number); // this adding zeros then becomes account number

        }while(accountRepository.existsByAccountNumber(accountNumber));// jab tak ye loop chalega jab tak unique acc no na mil jaye

        return accountNumber;
    }

    private AccountResponse mapToResponse(Account account){
        AccountResponse response = new AccountResponse();
        response.setId(account.getId());
        response.setAccountNumber(account.getAccountNumber());
        response.setUserId(account.getUserId());
        response.setAccountType(account.getAccountType());
        response.setEmail(account.getEmail());
        response.setPhone(account.getPhone());
        response.setStatus(account.getStatus());
        response.setBalance(account.getBalance());
        response.setAccountHolderName(account.getAccountHolderName());
        response.setDailyTransactionLimit(account.getDailyTransactionLimit());
        response.setCreatedAt(account.getCreatedAt());

        return response;
    }

}
