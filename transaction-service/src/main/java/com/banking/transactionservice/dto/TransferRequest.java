package com.banking.transactionservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TransferRequest {

    @NotBlank(message = "Sender account number is Required")
    private String senderAccountNumber;

    @NotBlank(message = "Sender account number is Required")
    private String receiverAccountNumber;

    @NotNull(message = "Sender account number is Required")
    @Positive(message = "Amount must be positive")
    private BigDecimal amount;

    private String description;

}
