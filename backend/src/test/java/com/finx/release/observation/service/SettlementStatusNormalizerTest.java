package com.finx.release.observation.service;

import com.finx.release.observation.model.SettlementObservationFinality;
import com.finx.release.observation.model.SettlementObservationReasonCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementStatusNormalizerTest {

    @ParameterizedTest
    @ValueSource(strings = {"SETTLED", "SUCCESS", "COMPLETED", "settled", "Success", "completed"})
    @DisplayName("SETTLED/SUCCESS/COMPLETED with settlementId and UTR maps to FINAL_SUCCESS")
    void settledWithUtrMapsToFinalSuccess(String status) {
        SettlementStatusNormalizer.NormalizedStatus result =
                SettlementStatusNormalizer.normalize(status, "MOCK-SETTL-01", "UTR-12345678");

        assertThat(result.finality()).isEqualTo(SettlementObservationFinality.FINAL_SUCCESS);
        assertThat(result.reasonCode()).isEqualTo(SettlementObservationReasonCodes.SETTLEMENT_FINAL_CONFIRMED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SETTLED", "SUCCESS", "COMPLETED"})
    @DisplayName("SETTLED without UTR maps to UNKNOWN with SETTLEMENT_FINAL_MISSING_UTR")
    void settledWithoutUtrMapsToUnknown(String status) {
        SettlementStatusNormalizer.NormalizedStatus nullUtr =
                SettlementStatusNormalizer.normalize(status, "MOCK-SETTL-01", null);
        assertThat(nullUtr.finality()).isEqualTo(SettlementObservationFinality.UNKNOWN);
        assertThat(nullUtr.reasonCode()).isEqualTo(SettlementObservationReasonCodes.SETTLEMENT_FINAL_MISSING_UTR);

        SettlementStatusNormalizer.NormalizedStatus blankUtr =
                SettlementStatusNormalizer.normalize(status, "MOCK-SETTL-01", "   ");
        assertThat(blankUtr.finality()).isEqualTo(SettlementObservationFinality.UNKNOWN);
        assertThat(blankUtr.reasonCode()).isEqualTo(SettlementObservationReasonCodes.SETTLEMENT_FINAL_MISSING_UTR);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SETTLED", "SUCCESS", "COMPLETED"})
    @DisplayName("SETTLED without providerSettlementId maps to UNKNOWN")
    void settledWithoutSettlementIdMapsToUnknown(String status) {
        SettlementStatusNormalizer.NormalizedStatus result =
                SettlementStatusNormalizer.normalize(status, null, "UTR-12345678");
        assertThat(result.finality()).isEqualTo(SettlementObservationFinality.UNKNOWN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PROCESSING", "SUBMITTED", "ACKNOWLEDGED", "INITIATED", "pending", "Initiated"})
    @DisplayName("In-flight statuses map to PENDING")
    void inFlightStatusesMapToPending(String status) {
        SettlementStatusNormalizer.NormalizedStatus result =
                SettlementStatusNormalizer.normalize(status, "MOCK-SETTL-01", null);
        assertThat(result.finality()).isEqualTo(SettlementObservationFinality.PENDING);
        assertThat(result.reasonCode()).isEqualTo(SettlementObservationReasonCodes.SETTLEMENT_PENDING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "REJECTED", "failed", "Rejected"})
    @DisplayName("Failure statuses map to FAILED")
    void failureStatusesMapToFailed(String status) {
        SettlementStatusNormalizer.NormalizedStatus result =
                SettlementStatusNormalizer.normalize(status, "MOCK-SETTL-01", null);
        assertThat(result.finality()).isEqualTo(SettlementObservationFinality.FAILED);
        assertThat(result.reasonCode()).isEqualTo(SettlementObservationReasonCodes.SETTLEMENT_FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REVERSED", "REFUNDED", "RETURNED", "reversed", "Returned"})
    @DisplayName("Reversal statuses map to REVERSED")
    void reversalStatusesMapToReversed(String status) {
        SettlementStatusNormalizer.NormalizedStatus result =
                SettlementStatusNormalizer.normalize(status, "MOCK-SETTL-01", null);
        assertThat(result.finality()).isEqualTo(SettlementObservationFinality.REVERSED);
        assertThat(result.reasonCode()).isEqualTo(SettlementObservationReasonCodes.SETTLEMENT_REVERSED);
    }

    @Test
    @DisplayName("NOT_FOUND maps to NOT_FOUND")
    void notFoundMapsToNotFound() {
        SettlementStatusNormalizer.NormalizedStatus result =
                SettlementStatusNormalizer.normalize("NOT_FOUND", null, null);
        assertThat(result.finality()).isEqualTo(SettlementObservationFinality.NOT_FOUND);
        assertThat(result.reasonCode()).isEqualTo(SettlementObservationReasonCodes.SETTLEMENT_NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FOO_BAR", "UNKNOWN_STATUS", "CUSTOM_CODE", ""})
    @DisplayName("Unrecognized or empty status maps to UNKNOWN with SETTLEMENT_STATUS_UNKNOWN")
    void unrecognizedStatusMapsToUnknown(String status) {
        SettlementStatusNormalizer.NormalizedStatus result =
                SettlementStatusNormalizer.normalize(status, "MOCK-SETTL-01", "UTR-12345678");
        assertThat(result.finality()).isEqualTo(SettlementObservationFinality.UNKNOWN);
        assertThat(result.reasonCode()).isEqualTo(SettlementObservationReasonCodes.SETTLEMENT_STATUS_UNKNOWN);
    }

    @Test
    @DisplayName("Null status maps to UNKNOWN with SETTLEMENT_STATUS_UNKNOWN")
    void nullStatusMapsToUnknown() {
        SettlementStatusNormalizer.NormalizedStatus result =
                SettlementStatusNormalizer.normalize(null, null, null);
        assertThat(result.finality()).isEqualTo(SettlementObservationFinality.UNKNOWN);
        assertThat(result.reasonCode()).isEqualTo(SettlementObservationReasonCodes.SETTLEMENT_STATUS_UNKNOWN);
    }
}
