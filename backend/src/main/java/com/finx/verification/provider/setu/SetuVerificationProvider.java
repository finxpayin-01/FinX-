package com.finx.verification.provider.setu;

import com.finx.verification.provider.VerificationProvider;
import com.finx.verification.provider.model.VerificationProviderModels.*;
import com.finx.verification.service.NameMatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Component("setuVerificationProvider")
public class SetuVerificationProvider implements VerificationProvider {

    private static final Logger log = LoggerFactory.getLogger(SetuVerificationProvider.class);

    private final String baseUrl;
    private final String clientId;
    private final String clientSecret;
    private final String productInstanceId;
    private final NameMatchService nameMatchService;
    private final WebClient webClient;

    public SetuVerificationProvider(
            @Value("${finx.verification.setu.base-url:https://qa.setu.co/api}") String baseUrl,
            @Value("${finx.verification.setu.client-id:}") String clientId,
            @Value("${finx.verification.setu.client-secret:}") String clientSecret,
            @Value("${finx.verification.setu.product-instance-id:}") String productInstanceId,
            NameMatchService nameMatchService) {
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl : "https://qa.setu.co/api";
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.productInstanceId = productInstanceId;
        this.nameMatchService = nameMatchService;
        this.webClient = WebClient.builder()
                .baseUrl(this.baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public String getProviderName() {
        return "SETU";
    }

    @Override
    public boolean isSandbox() {
        return baseUrl.contains("qa.setu.co") || baseUrl.contains("sandbox");
    }

    private boolean isConfigured() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }

    @Override
    public PanVerificationResult verifyPan(PanVerificationRequest request) {
        String correlationId = UUID.randomUUID().toString();
        log.info("[SETU] Initiating PAN verification. CorrelationId: {}", correlationId);

        if (!isConfigured()) {
            log.warn("[SETU] Credentials not configured for Setu provider. Returning NOT_CONFIGURED error.");
            return new PanVerificationResult(
                    false,
                    request.pan(),
                    null,
                    "PROVIDER_UNAVAILABLE",
                    null,
                    0.0,
                    "MISMATCH",
                    "SETU-UNCONFIGURED-" + correlationId,
                    "Setu verification provider credentials (SETU_CLIENT_ID / SETU_CLIENT_SECRET) are not configured"
            );
        }

        try {
            Map<String, Object> body = Map.of(
                    "pan", request.pan() != null ? request.pan().trim().toUpperCase() : "",
                    "consent", "Y",
                    "reason", "FinX KYC verification"
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.post()
                    .uri("/api/verify/pan")
                    .header("x-client-id", clientId)
                    .header("x-client-secret", clientSecret)
                    .header("x-product-instance-id", productInstanceId != null ? productInstanceId : "")
                    .header("x-request-id", correlationId)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            if (response != null && Boolean.TRUE.equals(response.get("success"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                String registeredName = data != null && data.get("full_name") != null ? data.get("full_name").toString() : "";
                String panStatus = data != null && data.get("status") != null ? data.get("status").toString() : "VALID";
                String category = data != null && data.get("category") != null ? data.get("category").toString() : "INDIVIDUAL";

                var matchEval = nameMatchService.compareNames(request.fullName(), registeredName);

                return new PanVerificationResult(
                        true,
                        request.pan(),
                        registeredName,
                        panStatus,
                        category,
                        matchEval.score(),
                        matchEval.result().name(),
                        "SETU-PAN-" + correlationId,
                        "PAN verified via Setu"
                );
            } else {
                String errMsg = response != null && response.get("message") != null ? response.get("message").toString() : "Verification failed";
                return new PanVerificationResult(
                        false, request.pan(), null, "INVALID", null, 0.0, "MISMATCH", "SETU-" + correlationId, errMsg
                );
            }
        } catch (WebClientResponseException e) {
            log.error("[SETU] HTTP error during PAN verification: status={} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            return new PanVerificationResult(
                    false, request.pan(), null, "ERROR", null, 0.0, "MISMATCH", "SETU-" + correlationId,
                    "Setu API error: " + e.getStatusCode().value() + " " + e.getStatusText()
            );
        } catch (Exception e) {
            log.error("[SETU] Unexpected error during PAN verification: {}", e.getMessage());
            return new PanVerificationResult(
                    false, request.pan(), null, "ERROR", null, 0.0, "MISMATCH", "SETU-" + correlationId,
                    "Setu call failed: " + e.getMessage()
            );
        }
    }

    @Override
    public GstVerificationResult verifyGstin(GstVerificationRequest request) {
        String correlationId = UUID.randomUUID().toString();
        log.info("[SETU] Initiating GSTIN verification. CorrelationId: {}", correlationId);

        if (!isConfigured()) {
            return new GstVerificationResult(
                    false, request.gstin(), null, null, "PROVIDER_UNAVAILABLE", null, null, null,
                    0.0, "MISMATCH", "SETU-UNCONFIGURED-" + correlationId,
                    "Setu credentials not configured"
            );
        }

        try {
            Map<String, Object> body = Map.of(
                    "gstin", request.gstin() != null ? request.gstin().trim().toUpperCase() : ""
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.post()
                    .uri("/api/verify/gstin")
                    .header("x-client-id", clientId)
                    .header("x-client-secret", clientSecret)
                    .header("x-product-instance-id", productInstanceId != null ? productInstanceId : "")
                    .header("x-request-id", correlationId)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            if (response != null && Boolean.TRUE.equals(response.get("success"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                String legalName = data != null && data.get("legal_name") != null ? data.get("legal_name").toString() : "";
                String tradeName = data != null && data.get("trade_name") != null ? data.get("trade_name").toString() : "";
                String status = data != null && data.get("status") != null ? data.get("status").toString() : "ACTIVE";
                String address = data != null && data.get("address") != null ? data.get("address").toString() : "";

                var matchEval = nameMatchService.compareNames(request.legalName(), legalName);

                return new GstVerificationResult(
                        true, request.gstin(), legalName, tradeName, status, LocalDate.now(), "Company", address,
                        matchEval.score(), matchEval.result().name(), "SETU-GST-" + correlationId, "GSTIN verified via Setu"
                );
            } else {
                return new GstVerificationResult(
                        false, request.gstin(), null, null, "INACTIVE", null, null, null,
                        0.0, "MISMATCH", "SETU-" + correlationId, "GSTIN not found or inactive in GSTN"
                );
            }
        } catch (Exception e) {
            log.error("[SETU] Error verifying GSTIN: {}", e.getMessage());
            return new GstVerificationResult(
                    false, request.gstin(), null, null, "ERROR", null, null, null,
                    0.0, "MISMATCH", "SETU-" + correlationId, "Setu GST verification failed: " + e.getMessage()
            );
        }
    }

    @Override
    public CinVerificationResult verifyCin(CinVerificationRequest request) {
        String correlationId = UUID.randomUUID().toString();
        log.info("[SETU] Verifying CIN/LLPIN: CorrelationId: {}", correlationId);

        if (!isConfigured()) {
            return new CinVerificationResult(
                    false, request.cinOrLlpin(), null, "PROVIDER_UNAVAILABLE", null, null, null,
                    0.0, "MISMATCH", "SETU-UNCONFIGURED-" + correlationId, "Setu credentials not configured"
            );
        }

        // Return NOT_SUPPORTED or delegate if Setu MCA endpoint is enabled
        return new CinVerificationResult(
                false, request.cinOrLlpin(), null, "NOT_SUPPORTED", null, null, null,
                0.0, "MANUAL_REVIEW", "SETU-" + correlationId,
                "MCA registry check not supported on current Setu tier; route to MANUAL_REVIEW"
        );
    }

    @Override
    public BankVerificationResult verifyBankAccount(BankVerificationRequest request) {
        String correlationId = UUID.randomUUID().toString();
        log.info("[SETU] Initiating penny drop verification. CorrelationId: {}", correlationId);

        if (!isConfigured()) {
            return new BankVerificationResult(
                    false, mask(request.accountNumber()), request.ifsc(), null, "Unknown Bank", null,
                    0.0, "MISMATCH", "SETU-UNCONFIGURED-" + correlationId, "Setu credentials not configured"
            );
        }

        try {
            Map<String, Object> body = Map.of(
                    "accountNumber", request.accountNumber(),
                    "ifsc", request.ifsc()
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.post()
                    .uri("/api/verify/bank-account")
                    .header("x-client-id", clientId)
                    .header("x-client-secret", clientSecret)
                    .header("x-product-instance-id", productInstanceId != null ? productInstanceId : "")
                    .header("x-request-id", correlationId)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();

            if (response != null && Boolean.TRUE.equals(response.get("success"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                String registeredName = data != null && data.get("beneficiary_name") != null ? data.get("beneficiary_name").toString() : "";
                String bankName = data != null && data.get("bank_name") != null ? data.get("bank_name").toString() : "Indian Bank";
                String branch = data != null && data.get("branch") != null ? data.get("branch").toString() : "";

                var matchEval = nameMatchService.compareNames(request.accountHolderName(), registeredName);

                return new BankVerificationResult(
                        true, mask(request.accountNumber()), request.ifsc(), registeredName, bankName, branch,
                        matchEval.score(), matchEval.result().name(), "SETU-BANK-" + correlationId,
                        "Penny drop completed via Setu"
                );
            } else {
                return new BankVerificationResult(
                        false, mask(request.accountNumber()), request.ifsc(), null, "", "",
                        0.0, "MISMATCH", "SETU-" + correlationId, "Penny drop failed at beneficiary bank"
                );
            }
        } catch (Exception e) {
            log.error("[SETU] Bank verification failed: {}", e.getMessage());
            return new BankVerificationResult(
                    false, mask(request.accountNumber()), request.ifsc(), null, "", "",
                    0.0, "MISMATCH", "SETU-" + correlationId, "Setu bank verification failed: " + e.getMessage()
            );
        }
    }

    @Override
    public IdentitySessionResult createIdentitySession(IdentitySessionRequest request) {
        String correlationId = UUID.randomUUID().toString();
        log.info("[SETU] Creating DigiLocker session. CorrelationId: {}", correlationId);

        if (!isConfigured()) {
            return new IdentitySessionResult(
                    "SETU-UNCONFIGURED-" + correlationId, null, "SETU-UNCONFIGURED", "PROVIDER_UNAVAILABLE"
            );
        }

        try {
            Map<String, Object> body = Map.of(
                    "redirectUrl", request.redirectUrl() != null ? request.redirectUrl() : "https://app.finx.com/onboarding/kyc/callback",
                    "callbackUrl", request.callbackUrl() != null ? request.callbackUrl() : "https://api.finx.com/api/v1/kyc/identity/callback"
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.post()
                    .uri("/api/digilocker/sessions")
                    .header("x-client-id", clientId)
                    .header("x-client-secret", clientSecret)
                    .header("x-product-instance-id", productInstanceId != null ? productInstanceId : "")
                    .header("x-request-id", correlationId)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            String sessionId = response != null && response.get("id") != null ? response.get("id").toString() : correlationId;
            String url = response != null && response.get("url") != null ? response.get("url").toString() : "";

            return new IdentitySessionResult(sessionId, url, "SETU-ID-" + sessionId, "CREATED");
        } catch (Exception e) {
            log.error("[SETU] Failed to create DigiLocker session: {}", e.getMessage());
            return new IdentitySessionResult(
                    "SETU-ERR-" + correlationId, null, "SETU-ERR", "FAILED"
            );
        }
    }

    @Override
    public IdentityStatusResult getIdentityStatus(String sessionId) {
        String correlationId = UUID.randomUUID().toString();
        log.info("[SETU] Fetching DigiLocker identity status for session: {}", sessionId);

        if (!isConfigured()) {
            return new IdentityStatusResult(
                    sessionId, "PROVIDER_UNAVAILABLE", null, null, null, null, 0.0,
                    "SETU-UNCONFIGURED", "Setu credentials not configured"
            );
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.get()
                    .uri("/api/digilocker/sessions/" + sessionId)
                    .header("x-client-id", clientId)
                    .header("x-client-secret", clientSecret)
                    .header("x-product-instance-id", productInstanceId != null ? productInstanceId : "")
                    .header("x-request-id", correlationId)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            if (response != null && "SUCCESS".equalsIgnoreCase(String.valueOf(response.get("status")))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                String fullName = data != null && data.get("name") != null ? data.get("name").toString() : "";
                String maskedAadhaar = data != null && data.get("maskedAadhaar") != null ? data.get("maskedAadhaar").toString() : "XXXX-XXXX-XXXX";
                String dob = data != null && data.get("dob") != null ? data.get("dob").toString() : "";
                String address = data != null && data.get("address") != null ? data.get("address").toString() : "";

                return new IdentityStatusResult(
                        sessionId, "VERIFIED", fullName, maskedAadhaar, dob, address, 100.0, "SETU-" + sessionId,
                        "Identity verified via DigiLocker"
                );
            } else {
                return new IdentityStatusResult(
                        sessionId, "PENDING", null, null, null, null, 0.0, "SETU-" + sessionId,
                        "Identity session pending or in-progress"
                );
            }
        } catch (Exception e) {
            log.error("[SETU] Failed to fetch DigiLocker status: {}", e.getMessage());
            return new IdentityStatusResult(
                    sessionId, "ERROR", null, null, null, null, 0.0, "SETU-" + sessionId,
                    "Failed to fetch Setu status: " + e.getMessage()
            );
        }
    }

    private String mask(String str) {
        if (str == null || str.length() <= 4) return "****";
        return "*".repeat(str.length() - 4) + str.substring(str.length() - 4);
    }
}
