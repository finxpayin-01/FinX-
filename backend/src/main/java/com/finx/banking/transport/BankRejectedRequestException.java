package com.finx.banking.transport;

/**
 * Thrown when the banking partner explicitly rejects a malformed or invalid request (HTTP 400 or HTTP 422).
 */
public class BankRejectedRequestException extends BankTransportException {

    private final int statusCode;

    public BankRejectedRequestException(int statusCode, String message) {
        super("BANK_REQUEST_REJECTED: HTTP " + statusCode + " - " + message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
