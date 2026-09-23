package com.finx.ramp.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RampConfig {

    private static final Logger log = LoggerFactory.getLogger(RampConfig.class);

    private final RampProperties rampProperties;

    public RampConfig(RampProperties rampProperties) {
        this.rampProperties = rampProperties;
    }

    @PostConstruct
    public void logConfigurationStatus() {
        if (rampProperties.isConfigured()) {
            log.info(
                    "[RAMP CONFIGURATION] Runtime credentials are configured for provider '{}' in '{}' environment.",
                    rampProperties.toStatus().provider(),
                    rampProperties.toStatus().environment()
            );
            return;
        }

        log.warn("[RAMP CONFIGURATION] Runtime credentials are not configured; ramp operations are disabled.");
    }
}
