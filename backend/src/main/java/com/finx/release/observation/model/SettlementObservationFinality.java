package com.finx.release.observation.model;

/**
 * Normalized finality state of trusted settlement observation.
 */
public enum SettlementObservationFinality {
    FINAL_SUCCESS,
    PENDING,
    FAILED,
    REVERSED,
    NOT_FOUND,
    UNKNOWN
}
