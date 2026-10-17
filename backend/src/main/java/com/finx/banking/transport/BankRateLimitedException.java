package com.finx.banking.transport;

/**
 * Thrown when the banking partner throttles requests (HTTP 429 Too Many Requests).
 * Transport layer MUST NOT automatically retry settlement POST requests upon rate limiting.
 */
public class BankRateLimitedException extends BankTransportException {

    private final String retryAfter;

    public BankRateLimitedException(String message, String retryAfter) {
        super("BANK_RATE_LIMITED: " + message);
        this.retryAfter = retryAfter;
    }

    public String getRetryAfter() {
        return retryAfter;
    }
}
