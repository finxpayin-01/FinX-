package com.finx.blockchain;

import java.math.BigInteger;
import java.util.UUID;

/** A vendor release is not evidence of bank settlement. */
public interface OffRampProvider {
    record Withdrawal(UUID intentId, String status, boolean simulated) {}
    Withdrawal request(UUID intentId, String vendorWallet, String token, BigInteger amountAtomic);

    class Unconfigured implements OffRampProvider {
        public Withdrawal request(UUID id,String wallet,String token,BigInteger amount) {
            throw new com.finx.exception.BadRequestException("Off-ramp provider is not configured; no bank payout requested");
        }
    }
    /** Explicit local test adapter. It never reports bank payment success. */
    class LocalSimulation implements OffRampProvider {
        public Withdrawal request(UUID id,String wallet,String token,BigInteger amount) {
            if (!ChainConfig.address(wallet) || !ChainConfig.address(token) || amount.signum()<=0)
                throw new IllegalArgumentException("Invalid simulated withdrawal");
            return new Withdrawal(id,"SIMULATED_PENDING",true);
        }
    }
}
