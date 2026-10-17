package com.finx.banking.transport;

/**
 * Thrown when the banking partner returns server errors (HTTP 5xx) or an invalid/unparseable protocol response.
 */
public class BankProtocolException extends BankTransportException {

    private final int statusCode;

    public BankProtocolException(int statusCode, String message) {
        super("BANK_PROTOCOL_ERROR: HTTP " + statusCode + " - " + message);
        this.statusCode = statusCode;
    }

    public BankProtocolException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 500;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
