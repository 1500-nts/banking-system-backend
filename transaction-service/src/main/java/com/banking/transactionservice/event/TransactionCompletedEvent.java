package com.banking.transactionservice.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TransactionCompletedEvent {

    private String transactionId;
    private String receiverAccountNumber;
    private String senderAccountNumber;
    private BigDecimal amount;
    private String description;
}
