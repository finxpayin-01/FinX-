package com.finx.banking.signing;

import java.time.Instant;
import java.util.Objects;

/**
 * Encapsulates the canonical parameters of an outbound banking HTTP request to be signed.
 */
public record BankSigningRequest(
        String method,
        String path,
        Instant timestamp,
        String requestId,
        byte[] bodyBytes
) {
    public BankSigningRequest {
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");
        if (bodyBytes == null) {
            bodyBytes = new byte[0];
        }
    }
}
