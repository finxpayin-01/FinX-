package com.finx.release.observation.service;

import com.finx.release.observation.model.SettlementObservationFinality;
import com.finx.release.observation.model.SettlementObservationReasonCodes;

import java.util.Locale;

/**
 * Normalizes external provider settlement statuses case-insensitively into canonical finality and reason codes.
 * Enforces the strict rule that FINAL_SUCCESS requires both nonblank providerSettlementId and nonblank utrNumber.
 */
public final class SettlementStatusNormalizer {

    private SettlementStatusNormalizer() {}

    public record NormalizedStatus(
            SettlementObservationFinality finality,
            String reasonCode
    ) {}

    public static NormalizedStatus normalize(String rawStatus, String providerSettlementId, String utrNumber) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return new NormalizedStatus(
                    SettlementObservationFinality.UNKNOWN,
                    SettlementObservationReasonCodes.SETTLEMENT_STATUS_UNKNOWN
            );
        }

        String normalized = rawStatus.trim().toUpperCase(Locale.ROOT);

        switch (normalized) {
            case "SETTLED":
            case "SUCCESS":
            case "COMPLETED":
                boolean hasSettlementId = providerSettlementId != null && !providerSettlementId.isBlank();
                boolean hasUtr = utrNumber != null && !utrNumber.isBlank();

                if (hasSettlementId && hasUtr) {
                    return new NormalizedStatus(
                            SettlementObservationFinality.FINAL_SUCCESS,
                            SettlementObservationReasonCodes.SETTLEMENT_FINAL_CONFIRMED
                    );
                } else if (!hasUtr) {
                    return new NormalizedStatus(
                            SettlementObservationFinality.UNKNOWN,
                            SettlementObservationReasonCodes.SETTLEMENT_FINAL_MISSING_UTR
                    );
                } else {
                    return new NormalizedStatus(
                            SettlementObservationFinality.UNKNOWN,
                            SettlementObservationReasonCodes.SETTLEMENT_STATUS_UNKNOWN
                    );
                }

            case "PENDING":
            case "PROCESSING":
            case "SUBMITTED":
            case "ACKNOWLEDGED":
            case "INITIATED":
                return new NormalizedStatus(
                        SettlementObservationFinality.PENDING,
                        SettlementObservationReasonCodes.SETTLEMENT_PENDING
                );

            case "FAILED":
            case "REJECTED":
                return new NormalizedStatus(
                        SettlementObservationFinality.FAILED,
                        SettlementObservationReasonCodes.SETTLEMENT_FAILED
                );

            case "REVERSED":
            case "REFUNDED":
            case "RETURNED":
                return new NormalizedStatus(
                        SettlementObservationFinality.REVERSED,
                        SettlementObservationReasonCodes.SETTLEMENT_REVERSED
                );

            case "NOT_FOUND":
                return new NormalizedStatus(
                        SettlementObservationFinality.NOT_FOUND,
                        SettlementObservationReasonCodes.SETTLEMENT_NOT_FOUND
                );

            default:
                return new NormalizedStatus(
                        SettlementObservationFinality.UNKNOWN,
                        SettlementObservationReasonCodes.SETTLEMENT_STATUS_UNKNOWN
                );
        }
    }
}
