package com.finx.banking.transport;

import com.finx.banking.signing.BankRequestSigner;
import com.finx.banking.signing.BankSigningRequest;
import com.finx.banking.signing.BankSigningResult;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Transport layer executing secure HTTPS requests against regulated banking partner endpoints.
 * Handles timeouts, header population, request signing, status code mapping, and bounded buffers.
 * <p>
 * STRICT INVARIANTS:
 * 1. ZERO automatic retry on POST settlement initiation requests.
 * 2. Safe logging only: never logs account numbers, client secrets, auth headers, private keys, or signatures.
 * 3. Maps HTTP status codes to typed exceptions (401/403 -> BankAuthenticationException, 429 -> BankRateLimitedException, etc.).
 */
public class BankHttpTransport {

    private static final Logger log = LoggerFactory.getLogger(BankHttpTransport.class);

    private final WebClient webClient;
    private final String providerName;
    private final String clientId;
    private final BankRequestSigner requestSigner; // Optional, null if not configured

    public BankHttpTransport(WebClient webClient, String providerName, String clientId) {
        this(webClient, providerName, clientId, null);
    }

    public BankHttpTransport(WebClient webClient, String providerName, String clientId, BankRequestSigner requestSigner) {
        this.webClient = Objects.requireNonNull(webClient, "webClient must not be null");
        this.providerName = Objects.requireNonNull(providerName, "providerName must not be null");
        this.clientId = clientId != null ? clientId : "";
        this.requestSigner = requestSigner;
    }

    public record TransportResponse(int statusCode, String body, HttpHeaders headers) {}

    /**
     * Executes an HTTP request to the banking partner with strict timeout, signing, and error mapping.
     * ZERO automatic retry: payout POST requests must fail cleanly to allow higher-layer idempotency handling.
     */
    public TransportResponse execute(HttpMethod method, String path, String bodyPayload, String operationKey) {
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(path, "path must not be null");

        String requestId = UUID.randomUUID().toString();
        Instant timestamp = Instant.now();
        byte[] bodyBytes = bodyPayload != null ? bodyPayload.getBytes(StandardCharsets.UTF_8) : new byte[0];

        log.info("Executing partner HTTP request: provider={} method={} path={} operationKey={} requestId={}",
                providerName, method, path, operationKey, requestId);

        long startNs = System.nanoTime();

        try {
            WebClient.RequestBodySpec spec = webClient.method(method)
                    .uri(path)
                    .header("X-Request-Id", requestId)
                    .header("X-Provider-Client-Id", clientId)
                    .header("X-Timestamp", String.valueOf(timestamp.toEpochMilli()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON);

            if (operationKey != null && !operationKey.isBlank()) {
                spec.header("X-Operation-Key", operationKey);
            }

            // Apply cryptographic signature if signer is configured
            if (requestSigner != null) {
                BankSigningRequest signingReq = new BankSigningRequest(method.name(), path, timestamp, requestId, bodyBytes);
                BankSigningResult signingRes = requestSigner.sign(signingReq);
                spec.header("X-Signature", signingRes.signature());
                spec.header("X-Signature-Algorithm", signingRes.algorithm());
                spec.header("X-Key-Id", signingRes.keyId());
            }

            WebClient.ResponseSpec responseSpec;
            if (bodyBytes.length > 0) {
                responseSpec = spec.bodyValue(bodyBytes).retrieve();
            } else {
                responseSpec = spec.retrieve();
            }

            var entity = responseSpec.toEntity(String.class).block();
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;

            if (entity == null) {
                throw new BankProtocolException(500, "Empty HTTP response entity from provider: " + providerName);
            }

            int statusCode = entity.getStatusCode().value();
            log.info("Partner HTTP response received: provider={} status={} durationMs={} operationKey={} requestId={}",
                    providerName, statusCode, durationMs, operationKey, requestId);

            return new TransportResponse(statusCode, entity.getBody(), entity.getHeaders());

        } catch (WebClientResponseException e) {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            int statusCode = e.getStatusCode().value();

            log.warn("Partner HTTP error response: provider={} status={} durationMs={} operationKey={} requestId={}",
                    providerName, statusCode, durationMs, operationKey, requestId);

            if (statusCode == 401 || statusCode == 403) {
                throw new BankAuthenticationException(statusCode, "Authentication rejected by provider: " + providerName);
            }
            if (statusCode == 400 || statusCode == 422) {
                throw new BankRejectedRequestException(statusCode, "Request rejected by provider: " + providerName);
            }
            if (statusCode == 429) {
                String retryAfter = e.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
                throw new BankRateLimitedException("Rate limit exceeded on provider: " + providerName, retryAfter);
            }
            if (statusCode >= 500) {
                throw new BankProtocolException(statusCode, "Provider server error: " + providerName);
            }
            throw new BankTransportException("HTTP error " + statusCode + " from provider: " + providerName, e);

        } catch (WebClientRequestException e) {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            Throwable cause = e.getCause();

            if (cause instanceof TimeoutException || cause instanceof ReadTimeoutException
                    || (cause != null && cause.getClass().getSimpleName().contains("Timeout"))) {
                log.error("Partner HTTP timeout: provider={} durationMs={} operationKey={} requestId={}",
                        providerName, durationMs, operationKey, requestId);
                throw new BankTimeoutException("Read/connect timeout communicating with provider: " + providerName, e);
            }

            log.error("Partner HTTP transport connection error: provider={} durationMs={} operationKey={} requestId={}",
                    providerName, durationMs, operationKey, requestId);
            throw new BankTransportException("Transport connection failure communicating with provider: " + providerName, e);

        } catch (Exception e) {
            if (e instanceof BankTransportException bte) {
                throw bte;
            }
            long durationMs = (System.nanoTime() - startNs) / 1_000_000;
            log.error("Partner HTTP unexpected exception: provider={} durationMs={} operationKey={} requestId={}",
                    providerName, durationMs, operationKey, requestId, e);
            throw new BankTransportException("Unexpected error during banking transport: " + e.getMessage(), e);
        }
    }

    public String getProviderName() {
        return providerName;
    }

    public String getClientId() {
        return clientId;
    }

    public BankRequestSigner getRequestSigner() {
        return requestSigner;
    }
}
