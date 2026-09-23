package com.finx.payment.service.onramp.onramper;

import com.finx.payment.dto.onramp.*;
import com.finx.payment.service.onramp.OnRampProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import java.math.BigDecimal;
import java.time.Instant;

@Service("onramper")
public class OnramperProvider implements OnRampProvider {
    private static final Logger log = LoggerFactory.getLogger(OnramperProvider.class);
    
    private final OnramperProperties properties;
    private final OnramperWebhookVerifier webhookVerifier;
    private final OnramperSignatureService signatureService;
    private final WebClient webClient;

    public OnramperProvider(OnramperProperties properties, OnramperWebhookVerifier webhookVerifier, OnramperSignatureService signatureService, WebClient.Builder builder) {
        this.properties = properties;
        this.webhookVerifier = webhookVerifier;
        this.signatureService = signatureService;
        this.webClient = builder.baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, properties.getApiKey())
                .build();
    }

    @Override
    public SupportedAssetsResponse getSupportedAssets(String sourceFiat, String country) {
        log.info("Checking supported assets for {} in {}", sourceFiat, country);
        try {
            Map<String, Object> response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/supported")
                    .queryParam("country", country)
                    .queryParam("fiat", sourceFiat)
                    .build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
                
            SupportedAssetsResponse res = new SupportedAssetsResponse();
            if (response != null) {
                res.setFiatCurrencies(List.of(sourceFiat));
                res.setCryptoAssets(List.of("USDC"));
            }
            return res;
        } catch (Exception e) {
            log.error("Failed to fetch supported assets", e);
            throw new RuntimeException("Provider capabilities check failed");
        }
    }

    @Override
    public PaymentMethodsResponse getPaymentMethods(String sourceFiat, String destinationCrypto, String country, String network) {
        PaymentMethodsResponse res = new PaymentMethodsResponse();
        res.setMethods(List.of("creditCard", "bankTransfer"));
        return res;
    }

    @Override
    public OnRampQuoteResponse createQuote(OnRampQuoteRequest request) {
        log.info("Creating quote for {} {}", request.getFiatAmount(), request.getFiatCurrency());
        try {
            Map<String, Object> response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/quotes")
                    .queryParam("fiatType", request.getFiatCurrency())
                    .queryParam("cryptoType", request.getCryptoAsset())
                    .queryParam("fiatAmount", request.getFiatAmount().toString())
                    .queryParam("country", request.getCountry() != null ? request.getCountry() : "IN")
                    .queryParam("network", request.getNetwork())
                    .build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();

            OnRampQuoteResponse res = new OnRampQuoteResponse();
            res.setQuoteReference(System.currentTimeMillis() + "_quote");
            res.setOnrampProvider(properties.getProvider());
            res.setFiatCurrency(request.getFiatCurrency());
            res.setFiatAmount(request.getFiatAmount());
            res.setCryptoAsset(request.getCryptoAsset());
            if (response != null && response.containsKey("payout")) {
                 res.setEstimatedCryptoAmount(new BigDecimal(response.get("payout").toString()));
            } else {
                 res.setEstimatedCryptoAmount(BigDecimal.ZERO);
            }
            res.setNetwork(request.getNetwork());
            res.setExpiresAt(Instant.now().plusSeconds(300));
            return res;
        } catch (Exception e) {
            log.error("Failed to get quote from Onramper", e);
            throw new RuntimeException("Provider quote failed");
        }
    }

    @Override
    public OnRampSessionResponse createSession(OnRampSessionRequest request) {
        log.info("Creating V2 checkout intent for Tx: {}", request.getTransactionId());
        String ipHash = signatureService.hashEndUserIp(request.getEndUserIp());
        
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("fiatAmount", request.getFiatAmount());
            body.put("fiatType", request.getFiatCurrency());
            body.put("cryptoType", request.getCryptoAsset());
            body.put("wallet", request.getDestinationWallet());
            body.put("network", request.getNetwork());
            body.put("merchantTransactionId", request.getTransactionId());
            body.put("endUserIpHash", ipHash);
            body.put("platform", "web");
            if (request.getRedirectUrl() != null) body.put("redirectUrl", request.getRedirectUrl());

            Map<String, Object> response = webClient.post()
                .uri("/checkout/intent")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();

            OnRampSessionResponse res = new OnRampSessionResponse();
            if (response != null && response.containsKey("checkoutUrl")) {
                res.setCheckoutUrl((String) response.get("checkoutUrl"));
                res.setProviderSessionId((String) response.get("intentId"));
            } else {
                res.setProviderSessionId("sess_" + request.getTransactionId());
                res.setCheckoutUrl(properties.getBaseUrl() + "/checkout/?sessionId=" + res.getProviderSessionId());
            }
            return res;
        } catch (Exception e) {
            log.error("Failed to create Checkout Intent V2", e);
            throw new RuntimeException("Failed to finalize Checkout Session");
        }
    }

    @Override
    public ProviderTransactionResponse getTransactionStatus(String providerTransactionId) {
        ProviderTransactionResponse res = new ProviderTransactionResponse();
        res.setStatus("unknown");
        return res;
    }

    @Override
    public WebhookVerificationResult verifyWebhook(String rawBody, String signature) {
        boolean valid = webhookVerifier.verifySignature(rawBody, signature, properties.getWebhookSecret());
        WebhookVerificationResult res = new WebhookVerificationResult(valid);
        if (valid) {
            if (rawBody.contains("\"type\":\"transaction_completed\"")) {
                res.setEventType("transaction_completed");
            }
        }
        return res;
    }
}
