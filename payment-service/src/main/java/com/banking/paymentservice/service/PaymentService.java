package com.banking.paymentservice.service;

import com.banking.paymentservice.dto.CreatePaymentRequest;
import com.banking.paymentservice.dto.PaymentOrderResponse;
import com.banking.paymentservice.entity.Payment;
import com.banking.paymentservice.entity.PaymentStatus;
import com.banking.paymentservice.repository.PaymentRepository;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.razorpay.Order;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;

    private final KafkaTemplate<String, Object> kafkaTemplate;


    //here we are setting the enviroment varible
    @Value("${razorpay.key-id}")
    private String keyId;

    @Value("${razorpay.key-secret}")
    private String keySecret;

    // making the Kafka event variables
    private static final String PAYMENT_COMPLETE_TOPIC = "payment.completed";
    private static final String PAYMENT_FAILED_TOPIC = "payment.failed";

    /*
     * Create Razorpay payment order
     *
     * FLOW ->
     * 1. Create order in Razorpay(payment api)
     * 2. Save Payment record in DB
     * 3. Return order details to frontend
     * 4. Frontend show Razorpay Checkout
     * 5. User pays
     *6. Razorpay calls webhook(automatically change the status)
     * */

    public PaymentOrderResponse createPaymentOrder(CreatePaymentRequest request) throws RazorpayException {

        log.info("Creating payment order for account: {} amount: {}",
                request.getAccountNumber(), request.getAmount());

        // in this you are passing two parameters payment keyId and keySecret
        // we can get these parameters from Razorpay payment gateway
        RazorpayClient razorpayClient = new RazorpayClient(keyId, keySecret);

        // Converting the current smaller unit
        int convertedAmount = request.getAmount()
                .multiply(BigDecimal.valueOf(100))
                .intValue();

        JSONObject orderRequest = new JSONObject();
        orderRequest.put("amount", convertedAmount);
        orderRequest.put("currency", "INR");
        // Generating the unique receipt id
        orderRequest.put("receipt", "rcpt_" + System.currentTimeMillis() + UUID.randomUUID().toString()
                .replace("-", "").substring(0,10));

        Order razorpayOrder = razorpayClient.orders.create(orderRequest);

        log.info("Razorpay Order created: {}", razorpayOrder.get("id").toString());

        //Save payment record
        Payment payment = new Payment();
        payment.setRazorpayOrderId(razorpayOrder.get("id").toString());
        payment.setAccountNumber(request.getAccountNumber());
        payment.setAmount(request.getAmount());
        payment.setCurrency("INR");
        payment.setStatus(PaymentStatus.CREATED);
        payment.setDescription(request.getDescription());

        Payment savedPayment = paymentRepository.save(payment);

        return new PaymentOrderResponse(
                savedPayment.getId(),
                razorpayOrder.get("id").toString(),
                request.getAmount(),
                "INR",
                "CREATED",
                keyId);
    }

    // WEBHOOK capture the all event and that response related to payment send back to the backend
    // webhook is our fronted part that capture the event and send to the backend
    // eg. if payment got failed then webhook capture that event at frontend and send it to the backend(notifying the backend)

    public void handleWebhook(Map<String, Object> payload){
        log.info("Received Razorpay webhook: {}", payload.get("event"));

        String event = (String) payload.get("event");

        if("payment.completed".equals(event)){
            handlePaymentSuccess(payload);
        }
        else if("payment.failed".equals(event)){
            handledPaymentFailure(payload);
        }
    }

    private void handlePaymentSuccess(Map<String , Object> payload){
        try{

            Map<String, Object> paymentData = extractPaymentData(payload);
            String orderId = (String) paymentData.get("orderId");
            String paymentId = (String) paymentData.get("id");

            Payment payment = paymentRepository.findByRazorpayOrderId(orderId)
                    .orElseThrow(() -> new RuntimeException("Payment not found for order: "+ orderId));

            payment.setRazorpayPaymentId(paymentId);
            payment.setStatus(PaymentStatus.COMPLETED);
            paymentRepository.save(payment);

            // public payment completed event
            Map<String, Object> event = new HashMap<>();
            event.put("paymentId", payment.getId());
            event.put("accountNumber", payment.getAccountNumber());
            event.put("amount", payment.getAmount());
            event.put("razorpayPaymentId", paymentId);

            kafkaTemplate.send(PAYMENT_COMPLETE_TOPIC, payment.getId(), event);

            log.info("Payment completed: {}", payment.getId());

        }catch (Exception e){
            log.error("Error handling payment success: {}", e.getMessage());
        }
    }

    private void handledPaymentFailure(Map<String, Object> payload){
        try{

            Map<String, Object> paymentData = extractPaymentData(payload);
            String orderId = (String) paymentData.get("orderId");

            Payment payment = paymentRepository.findByRazorpayOrderId(orderId)
                    .orElseThrow(() -> new RuntimeException("Payment not found for order: "+ orderId));

            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason("Pay failed via Razorpay");
            paymentRepository.save(payment);

            // public payment failure event
            Map<String, Object> event = new HashMap<>();
            event.put("paymentId", payment.getId());
            event.put("accountNumber", payment.getAccountNumber());
            event.put("amount", payment.getAmount());
            event.put("reason", "Pay failed via Razorpay");

            kafkaTemplate.send(PAYMENT_FAILED_TOPIC, payment.getId(), event);

            log.warn("Payment failed: {}", payment.getId());

        }catch (Exception e){
            log.error("Error handling payment failure: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractPaymentData(Map<String, Object> payload) {
        Map<String, Object> entity = (Map<String, Object>) payload.get("payload");
        Map<String, Object> paymentWrapper = (Map<String, Object>) entity.get("payment");
        return (Map<String, Object>) paymentWrapper.get("entity");
    }

}




















