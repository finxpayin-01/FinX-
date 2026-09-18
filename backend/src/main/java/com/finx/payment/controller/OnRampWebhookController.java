package com.finx.payment.controller;

import com.finx.payment.dto.onramp.WebhookVerificationResult;
import com.finx.payment.service.onramp.OnRampProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/webhooks")
public class OnRampWebhookController {

    private static final Logger log = LoggerFactory.getLogger(OnRampWebhookController.class);
    private final OnRampProvider onRampProvider;

    public OnRampWebhookController(@Qualifier("onramper") OnRampProvider onRampProvider) {
        this.onRampProvider = onRampProvider;
    }

    @PostMapping("/onramp")
    public ResponseEntity<String> handleOnRampWebhook(
            @RequestHeader("X-Onramper-Webhook-Signature") String signature,
            @RequestBody String rawBody) {
        
        log.info("Received Onramp Webhook...");
        WebhookVerificationResult result = onRampProvider.verifyWebhook(rawBody, signature);
        
        if (!result.isValid()) {
            log.error("Invalid Webhook Signature.");
            return ResponseEntity.status(401).body("Invalid Signature");
        }
        
        // Deduplicate using WebhookEvent entity
        // Parse rawBody -> determine provider event -> initiate blockchain check if successful
        log.info("Webhook successfully verified.");
        
        return ResponseEntity.ok("Received");
    }
}
