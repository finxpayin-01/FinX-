package com.finx.release.observation.model;

/**
 * Authoritative external origin of trusted settlement observation.
 */
public enum SettlementObservationSource {
    WEBHOOK,
    STATUS_POLL,
    OPERATION_LOOKUP
}
