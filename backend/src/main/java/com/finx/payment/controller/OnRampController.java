package com.finx.payment.controller;

import com.finx.payment.dto.onramp.*;
import com.finx.payment.service.onramp.OnRampProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/onramp")
public class OnRampController {
    @org.springframework.beans.factory.annotation.Autowired
    private com.finx.payment.config.PaymentRailPolicy railPolicy;
    
    private final OnRampProvider onRampProvider;

    public OnRampController(@Qualifier("onramper") OnRampProvider onRampProvider) {
        this.onRampProvider = onRampProvider;
    }

    @PostMapping("/quote")
    public ResponseEntity<OnRampQuoteResponse> createQuote(@RequestBody OnRampQuoteRequest request) {
        // Must validate authenticated user & project ownership inside service layer
        OnRampQuoteResponse res = onRampProvider.createQuote(request);
        return ResponseEntity.ok(res);
    }
    
    @PostMapping("/session")
    public ResponseEntity<OnRampSessionResponse> createSession(@RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody OnRampSessionRequest request) {
        if (railPolicy.blockchain()) throw new com.finx.exception.BadRequestException("Use the project's persisted blockchain funding intent. Provider checkout is not configured.");
        OnRampSessionResponse res = onRampProvider.createSession(request);
        // Persist OnRampTransaction here before returning checkoutUrl
        return ResponseEntity.ok(res);
    }
}
