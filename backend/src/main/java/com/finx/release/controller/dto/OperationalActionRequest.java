package com.finx.release.controller.dto;

import jakarta.validation.constraints.Size;

/**
 * Request payload for administrative operational actions (hold, resume, cancel).
 * Contains only a reasonCode; NO amount, NO destination, NO credentials.
 */
public record OperationalActionRequest(
        @Size(max = 100, message = "reasonCode must not exceed 100 characters")
        String reasonCode
) {}
