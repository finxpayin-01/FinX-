package com.finx.banking.capability;

import com.finx.exception.AppException;

/**
 * Thrown when an operation is requested on a provider where required configuration,
 * security material, or official bank API specifications are absent or unconfigured.
 */
public class BankingCapabilityNotConfiguredException extends AppException {

    public BankingCapabilityNotConfiguredException(String message) {
        super(message);
    }

    public BankingCapabilityNotConfiguredException(String message, Throwable cause) {
        super(message, cause);
    }
}
