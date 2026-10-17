package com.finx.banking.transport;

/**
 * Thrown when an HTTP connection, handshake, or read exceeds configured timeout thresholds.
 */
public class BankTimeoutException extends BankTransportException {

    public BankTimeoutException(String message) {
        super(message);
    }

    public BankTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
