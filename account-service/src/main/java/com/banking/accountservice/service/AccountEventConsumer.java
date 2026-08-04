package com.banking.accountservice.service;
/*
 * Here we consume the event -> complete transaction and fraud detection both
 * to block the acc and to credit the balance
 * */

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountEventConsumer {

    private final AccountService accountService;

    /*Consume transaction.completed event from kafka
    //Credits receiver account
    * */
    @KafkaListener(topics = "transaction.complete")
    public void consumeTransactionCompleted(
            @Payload Map<String, Object> payload){
        try{

            String receiverAccount = (String) payload.get("receiverAccountNumber");
            BigDecimal amount = new BigDecimal(payload.get("amount").toString());

            log.info("Crediting account: {} amount: {}", receiverAccount, amount);
            accountService.creditBalance(receiverAccount, amount);

        }catch (Exception e){
            log.error("Error crediting account: {}", e.getMessage());
        }
    }

    /*Consume fraud.detected event from kafka
     * Block the flagged account
     * */
    @KafkaListener(topics = "fraud.detected")
    public void consumeFraudDetected(
            @Payload Map<String, Object> payload){
        try {

            String accountNumber = (String) payload.get("accountNumber");
            log.info("Fraud detected - blocking account: {}", accountNumber);

            accountService.blockAccount(accountNumber);
        }catch (Exception e){
            log.error("Error blocking account: {}", e.getMessage());
        }
    }

    /*Consume payment.completed event from kafka (published by
     * payment-service after Razorpay confirms a payment via webhook).
     * This is what actually turns a successful external deposit into a
     * real balance increase - without this listener, payment-service
     * only updates its own Payment record and notification-service just
     * sends a notification; the account balance itself never moves.
     * */
    @KafkaListener(topics = "payment.completed")
    public void consumePaymentCompleted(
            @Payload Map<String, Object> payload){
        try {

            String accountNumber = (String) payload.get("accountNumber");
            BigDecimal amount = new BigDecimal(payload.get("amount").toString());

            log.info("Payment completed - crediting account: {} amount: {}",
                    accountNumber, amount);
            accountService.creditBalance(accountNumber, amount);

        }catch (Exception e){
            log.error("Error crediting account after payment: {}", e.getMessage());
        }
    }
}
