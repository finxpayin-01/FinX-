package com.finx.banking.capability;

import java.util.*;

/**
 * Immutable container for a banking provider's declared capabilities.
 */
public final class BankingProviderCapabilities {

    private final Set<BankingProviderCapability> capabilities;

    private BankingProviderCapabilities(Set<BankingProviderCapability> capabilities) {
        this.capabilities = Collections.unmodifiableSet(new HashSet<>(capabilities));
    }

    public static BankingProviderCapabilities of(BankingProviderCapability... capabilities) {
        if (capabilities == null || capabilities.length == 0) {
            return new BankingProviderCapabilities(Collections.emptySet());
        }
        return new BankingProviderCapabilities(EnumSet.copyOf(Arrays.asList(capabilities)));
    }

    public static BankingProviderCapabilities of(Set<BankingProviderCapability> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return new BankingProviderCapabilities(Collections.emptySet());
        }
        return new BankingProviderCapabilities(EnumSet.copyOf(capabilities));
    }

    public static BankingProviderCapabilities empty() {
        return new BankingProviderCapabilities(Collections.emptySet());
    }

    public boolean supports(BankingProviderCapability capability) {
        return capability != null && capabilities.contains(capability);
    }

    public boolean supportsAll(Collection<BankingProviderCapability> required) {
        if (required == null || required.isEmpty()) {
            return true;
        }
        return capabilities.containsAll(required);
    }

    public Set<BankingProviderCapability> asSet() {
        return capabilities;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BankingProviderCapabilities that = (BankingProviderCapabilities) o;
        return Objects.equals(capabilities, that.capabilities);
    }

    @Override
    public int hashCode() {
        return Objects.hash(capabilities);
    }

    @Override
    public String toString() {
        return "BankingProviderCapabilities" + capabilities;
    }
}
