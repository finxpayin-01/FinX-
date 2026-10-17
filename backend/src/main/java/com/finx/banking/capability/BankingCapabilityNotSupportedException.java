package com.finx.banking.capability;

import com.finx.exception.AppException;

/**
 * Thrown when an operation is requested that the configured banking provider explicitly does not support.
 */
public class BankingCapabilityNotSupportedException extends AppException {

    private final String providerName;
    private final BankingProviderCapability capability;

    public BankingCapabilityNotSupportedException(String providerName, BankingProviderCapability capability) {
        super("CAPABILITY_NOT_SUPPORTED: Banking provider '" + providerName + "' does not support capability: " + capability);
        this.providerName = providerName;
        this.capability = capability;
    }

    public String getProviderName() {
        return providerName;
    }

    public BankingProviderCapability getCapability() {
        return capability;
    }
}
