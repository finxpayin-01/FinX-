package com.finx.release.model;

/**
 * Lifecycle states for durable escrow fund reservations.
 */
public enum ReleaseReservationStatus {
    /**
     * Funds are actively reserved, preventing concurrent release claims.
     */
    ACTIVE,

    /**
     * Funds have been consumed by trusted settlement finalization.
     */
    CONSUMED,

    /**
     * Reservation was released/unreserved due to failure or cancellation.
     */
    RELEASED
}
