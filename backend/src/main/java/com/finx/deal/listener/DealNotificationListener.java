package com.finx.deal.listener;

import com.finx.deal.event.DealCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class DealNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(DealNotificationListener.class);

    @org.springframework.beans.factory.annotation.Value("${mailersend.api-key:${MAILERSEND_API_KEY:}}")
    private String mailerSendApiKey;

    @org.springframework.beans.factory.annotation.Value("${mailersend.domain:${MAILERSEND_DOMAIN:}}")
    private String mailerSendDomain;

    public DealNotificationListener() {
    }

    @Async
    @EventListener
    public void handleDealCreated(DealCreatedEvent event) {
        log.info("[ASYNC EMAIL DISPATCH] Received deal creation event for deal: {}", event.getDeal().getId());
        
        try {
            if (mailerSendApiKey != null && !mailerSendApiKey.isBlank()) {
                sendViaMailerSend(event);
            } else {
                log.warn("MailerSend API key missing, skipping email dispatch for: {}", event.getVendorEmail());
            }
        } catch (Exception e) {
            log.error("Failed to send async email to {}", event.getVendorEmail(), e);
        }
    }

    private void sendViaMailerSend(DealCreatedEvent event) throws Exception {
        org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("Authorization", "Bearer " + mailerSendApiKey);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        String fromEmail = "finx-no-reply@" + (mailerSendDomain != null && !mailerSendDomain.isBlank() ? mailerSendDomain : "trial-z3m5jgr5rkgndpyo.mlsender.net");
        String subject = "New FinX Project Assigned: " + event.getDeal().getTitle();
        String text = "Hello,\n\nYou have been assigned a new project on FINX: " + event.getDeal().getTitle() + 
                      "\nAmount: " + event.getDeal().getTotalAmount() + " " + event.getDeal().getCurrency() + 
                      "\nProject ID: " + event.getDeal().getProjectId() + 
                      "\n\nPlease login to accept the project.";

        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("from", java.util.Map.of("email", fromEmail, "name", "FINX Platform"));
        body.put("to", java.util.List.of(java.util.Map.of("email", event.getVendorEmail())));
        body.put("subject", subject);
        body.put("text", text);
        body.put("html", "<p>" + text.replace("\n", "<br>") + "</p>");

        org.springframework.http.HttpEntity<java.util.Map<String, Object>> entity = new org.springframework.http.HttpEntity<>(body, headers);

        org.springframework.http.ResponseEntity<String> response = restTemplate.postForEntity("https://api.mailersend.com/v1/email", entity, String.class);
        
        if (response.getStatusCode().is2xxSuccessful()) {
            log.info(">>>> Successfully dispatched email via MailerSend to: {}", event.getVendorEmail());
        } else {
            log.error("Failed to send email via MailerSend. Status: {}, Body: {}", response.getStatusCode(), response.getBody());
        }
    }
}
