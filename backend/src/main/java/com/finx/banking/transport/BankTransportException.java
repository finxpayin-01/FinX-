package com.finx.banking.transport;

import com.finx.exception.AppException;

/**
 * Base exception for all low-level banking partner HTTP transport and protocol failures.
 */
public class BankTransportException extends AppException {

    public BankTransportException(String message) {
        super(message);
    }

    public BankTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
