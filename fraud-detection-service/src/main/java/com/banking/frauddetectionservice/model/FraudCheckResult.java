package com.banking.frauddetectionservice.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FraudCheckResult {
    // storing the fraud result
    //the is fraud is true or not
    //and what is the reason of fraud

    private boolean fraud;
    private String reason;

}
