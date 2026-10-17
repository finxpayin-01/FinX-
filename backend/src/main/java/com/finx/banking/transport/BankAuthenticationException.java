package com.finx.banking.transport;

/**
 * Thrown when the banking partner rejects authentication (HTTP 401 Unauthorized or HTTP 403 Forbidden).
 */
public class BankAuthenticationException extends BankTransportException {

    private final int statusCode;

    public BankAuthenticationException(int statusCode, String message) {
        super("BANK_AUTH_FAILED: HTTP " + statusCode + " - " + message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
