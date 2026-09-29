package com.finx.verification.config;

import com.finx.verification.provider.VerificationProvider;
import com.finx.verification.provider.mock.MockVerificationProvider;
import com.finx.verification.provider.setu.SetuVerificationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class VerificationProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(VerificationProviderConfig.class);

    @Value("${finx.verification.provider:mock}")
    private String configuredProvider;

    @Bean
    @Primary
    public VerificationProvider activeVerificationProvider(
            MockVerificationProvider mockProvider,
            SetuVerificationProvider setuProvider) {
        log.info("[FinX Verification] Active provider configured as: '{}'", configuredProvider);

        if ("setu".equalsIgnoreCase(configuredProvider.trim())) {
            log.info("[FinX Verification] Using Setu Verification Provider");
            return setuProvider;
        }

        log.info("[FinX Verification] Using Mock Verification Provider (SANDBOX SIMULATION)");
        return mockProvider;
    }
}
