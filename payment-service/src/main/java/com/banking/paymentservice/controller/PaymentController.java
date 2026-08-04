package com.banking.paymentservice.controller;

import com.banking.paymentservice.dto.CreatePaymentRequest;
import com.banking.paymentservice.dto.PaymentOrderResponse;
import com.banking.paymentservice.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments")
@Slf4j
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    @Value("${razorpay.webhook-secret}")
    private String webhookSecret;

    @PostMapping
    public ResponseEntity<PaymentOrderResponse> createdPaymentsOrder(
            @Valid @RequestBody CreatePaymentRequest request) throws RazorpayException {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentService.createPaymentOrder(request));
    }

    /*
     * Razorpay webhook endpoint - PUBLIC at the Gateway (Razorpay's
     * servers can't send a JWT), so signature verification here IS the
     * actual security boundary, not an optional extra.
     *
     * IMPORTANT: this takes the RAW request body as a String, not a
     * parsed Map. HMAC signature verification is byte-exact - if Spring
     * parses the JSON into a Map first and we later re-serialize it to
     * verify, differences in key ordering/whitespace/number formatting
     * would make a genuine Razorpay request fail verification. The raw
     * string is parsed into a Map only AFTER the signature checks out.
     */
    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(
            @RequestBody String rawPayload,
            @RequestHeader("X-Razorpay-Signature") String signature) {

        boolean valid;
        try {
            valid = Utils.verifyWebhookSignature(rawPayload, signature, webhookSecret);
        } catch (RazorpayException e) {
            log.warn("Webhook signature verification threw an exception: {}", e.getMessage());
            valid = false;
        }

        if (!valid) {
            log.warn("Rejected webhook call with invalid/missing signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
        }

        log.info("Webhook received from Razorpay (signature verified)");

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(rawPayload, Map.class);
            paymentService.handleWebhook(payload);
        } catch (Exception e) {
            log.error("Failed to parse verified webhook payload: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Malformed payload");
        }

        return ResponseEntity.ok("Webhook processed");
    }
}
