package com.banking.frauddetectionservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

import java.math.BigDecimal;

@Service
@FeignClient(name = "account-service", url = "${account.service.url}")
public interface AccountServiceClient {

    // Here we are getting real amount from Account Service
    // This is an internal system-to-system call (not a user request routed
    // through the Gateway), so we identify ourselves as a trusted internal
    // service with ADMIN-level role to satisfy account-service's ownership
    // check (enforceOwnership allows any account when role == ADMIN).
    @GetMapping("/api/v1/accounts/{accountNumber}/balance")
    BigDecimal getBalance(
            @PathVariable String accountNumber,
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader("X-User-Role") String userRole);

}