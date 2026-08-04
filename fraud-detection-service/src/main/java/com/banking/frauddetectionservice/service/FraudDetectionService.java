package com.banking.frauddetectionservice.service;

import com.banking.frauddetectionservice.client.AccountServiceClient;
import com.banking.frauddetectionservice.model.FraudCheckResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
@EnableFeignClients(basePackages = "com.banking.frauddetectionservice.client")
public class FraudDetectionService {

    private final AccountServiceClient accountServiceClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RedisTemplate<String, String> redisTemplate;

    // For these below variable we have set the environment variables in yaml file
    @Value("${fraud.max-transaction-per-minute}")
    private int maxTransactionPerMinute;

    @Value("${fraud.suspicious-amount-multiplier}")
    private double suspiciousAmountMultiplier;

    @Value("${fraud.max-balance-percentage}")
    private double maxBalancePercentage;

    // this is for Event
    private static final String VERIFICATION_REQUIRED_TOPIC = "verification.required";
    private static final String FRAUD_CHECK_CLEAN_RESULT_TOPIC = "fraud.check.clean";

    public void checkTransaction(Map<String, Object> payload){
        String transactionId = (String)payload.get("transactionId");
        String accountNumber = (String)payload.get("senderAccountNumber");
        BigDecimal amount = new BigDecimal(payload.get("amount").toString());

        // Fetch real balance from Account Service
        // Identify as an internal trusted service (ADMIN role) so account-service's
        // per-user ownership check allows this system-to-system lookup.
        BigDecimal senderBalance = accountServiceClient.getBalance(
                accountNumber, "fraud-detection-service", "ADMIN");
        log.info("Checking transaction: {} account: {} amount: {} balance: {}",
                transactionId, accountNumber, amount, senderBalance);

        // Here storing the Fraud info in result
        // for this check the FraudCheckResult Class
        // performFraudChecks method give me the result info
        FraudCheckResult result = performFraudChecks(accountNumber, amount, senderBalance);

        // Here the fraud is true, so we do the verification
        /*Here we are verifying the user via OTP for verifying
         *Here we are generating this event and this event is Consume by Transaction Service
         * And transaction Service is generating the OTP
         * And this OTP event is Consume via by Notification
         * Hare we send the Otp to the user via SMS or Email
         * for that we are calling the API that is verifyOTP(present in TransactionController)
         * then the check whether it block if otp not verify via the user or proceed the transactio if verify by the user
         *That why we have not coded verifyOTP before THIS IS VERY IMPORTANT
         * */
        if(result.isFraud()){

            log.info("Suspicious activity detected - account: {}" +
                            "reason: {} - requesting OTP verification",
                    accountNumber, result.getReason());

            Map<String, Object> verificationEvent = new HashMap<>();
            verificationEvent.put("transactionId", transactionId);
            verificationEvent.put("accountNumber", accountNumber);
            verificationEvent.put("amount", amount);
            verificationEvent.put("reason", result.getReason());

            kafkaTemplate.send(VERIFICATION_REQUIRED_TOPIC, transactionId, verificationEvent);
        }
        else{
            //Transaction is clean
            log.info("Transaction clean");

            Map<String, Object> transactionCleanEvent = new HashMap<>();
            transactionCleanEvent.put("transactionId", transactionId);
            transactionCleanEvent.put("isFraud",false);
            transactionCleanEvent.put("reason", null);

            kafkaTemplate.send(FRAUD_CHECK_CLEAN_RESULT_TOPIC, transactionId, transactionCleanEvent);

        }
    }

    private FraudCheckResult performFraudChecks(
            String accountNumber,
            BigDecimal amount,
            BigDecimal senderBalance){

        /* In this we are checking 3 Patterns ->
         * 1. velocity check ->
         * in this fraudesters they do not done fraud manually
         * they run a script that contains the 5, 10, 20 and more than that
         * transaction in one second.
         * if this happens we found suspicious things and fraud detected
         * then the fraud is verified by the user is the proceed transaction otherwise do the blocking things
         *
         * 2. if the transaction amount is 3x, 4x, 5x, 6x of avg transaction amount
         * then it found to be suspicious then go for the user verification(via OTP) process
         * if the user verify then we can proceed our transaction
         *
         * 3. if the transaction amount is 90% of the user balance then it found to suspicious
         * */

        //Pattern : velocity check
        if(isVelocityExceeded(accountNumber)){
            return new FraudCheckResult(
                    true,"Too many transaction in 60 seconds"+ "- Velocity limit exceeded");
        }

        //Pattern 2 : Amount check
        if(isAmountSuspicious(accountNumber, amount)){
            return new FraudCheckResult(
                    true, "Unusual transaction amount"+
                    " - exceeds 3x your average");
        }

        //Pattern 3: Balance check
        if(senderBalance.compareTo(BigDecimal.ZERO) > 0
                && isBalanceCheckFailed(senderBalance, amount)){
            return new FraudCheckResult(
                    true, "Transaction exceed 90% of account balance");
        }

        // All Patterns fails then Clean Transaction
        return new FraudCheckResult(false, null);
    }

    private boolean isVelocityExceeded(String accountNumber){
        //using redis
        String key = "fraud:velocity" + accountNumber;
        Long count = redisTemplate.opsForValue().increment(key);

        //the key deleted automatically after 60 sec starts when the count == 1 at this the time starts
        if(count != null && count == 1){
            redisTemplate.expire(key, 60, TimeUnit.SECONDS);
        }

        log.info("Velocity check - account: {} count: {}/{}",
                accountNumber, count, maxTransactionPerMinute);

        return count != null && count >= maxTransactionPerMinute;
    }

    private boolean isAmountSuspicious(String accountNumber, BigDecimal amount){
        String avgKey = "fraud:avg_amount" + accountNumber;
        String avgStr = redisTemplate.opsForValue().get(avgKey);

        if(avgStr == null){
            redisTemplate.opsForValue().set(avgKey, amount.toString());
            return false;
        }

        BigDecimal avgAmount = new BigDecimal(avgStr);
        BigDecimal threshold = avgAmount.multiply(
                BigDecimal.valueOf(suspiciousAmountMultiplier));

        //update running avg
        BigDecimal newAvg = avgAmount.add(amount)
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

        redisTemplate.opsForValue().set(avgKey, newAvg.toString());

        log.info("Amount check - amount: {} threshold {} suspicious: {}",
                amount, threshold, amount.compareTo(threshold) > 0);
        return amount.compareTo(threshold) > 0;
    }

    // Here we are checking the amoun >= 90% of balance
    private boolean isBalanceCheckFailed(BigDecimal senderBalance, BigDecimal amount){

        BigDecimal maxAllowed = senderBalance.multiply(
                BigDecimal.valueOf(maxBalancePercentage));

        log.info("Balance check - amount: {} maxAllowed: {} suspicious {}",
                amount, maxAllowed, amount.compareTo(maxAllowed) > 0);

        return amount.compareTo(maxAllowed) > 0;
    }

}