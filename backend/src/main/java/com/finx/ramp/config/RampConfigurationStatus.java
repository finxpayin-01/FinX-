package com.finx.ramp.config;

public record RampConfigurationStatus(
        boolean configured,
        String provider,
        String environment
) {
}
