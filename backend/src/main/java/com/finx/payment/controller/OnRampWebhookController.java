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
    @org.springframework.beans.factory.annotation.Autowired
    private com.finx.payment.service.onramp.OnRampWebhookInbox inbox;

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
        
        try {
            inbox.receive(rawBody, signature);
        } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
            return ResponseEntity.ok("Already received");
        }
        log.info("Webhook successfully verified.");
        
        return ResponseEntity.ok("Received");
    }
}
