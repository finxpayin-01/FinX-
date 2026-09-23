package com.finx.ramp.config;

import com.finx.exception.PaymentGatewayUnavailableException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "finx.ramp")
public class RampProperties {

    private String apiKey;
    private String apiSecret;
    private String provider = "UNSPECIFIED";
    private String environment = "sandbox";

    public boolean isConfigured() {
        return hasText(apiKey) && hasText(apiSecret);
    }

    public void requireConfiguredForOperation() {
        if (!isConfigured()) {
            throw new PaymentGatewayUnavailableException(
                    "Ramp provider credentials are not configured for backend runtime operations."
            );
        }
    }

    public RampConfigurationStatus toStatus() {
        return new RampConfigurationStatus(
                isConfigured(),
                hasText(provider) ? provider : "UNSPECIFIED",
                hasText(environment) ? environment : "sandbox"
        );
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public void setApiSecret(String apiSecret) {
        this.apiSecret = apiSecret;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }
}
