package com.finx.banking.signing;

/**
 * Abstraction for signing outbound HTTP requests sent to regulated banking partners.
 * Implementations use deterministic canonical string formats and cryptographic algorithms (e.g. HMAC-SHA256, RSA-SHA256).
 */
public interface BankRequestSigner {

    String getAlgorithm();

    BankSigningResult sign(BankSigningRequest request);
}
