package com.finx.banking.signing;

import java.util.Objects;

/**
 * Result of signing a canonical banking request.
 */
public record BankSigningResult(
        String signature,
        String algorithm,
        String keyId
) {
    public BankSigningResult {
        Objects.requireNonNull(signature, "signature must not be null");
        Objects.requireNonNull(algorithm, "algorithm must not be null");
    }
}
