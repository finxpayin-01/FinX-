package com.finx.payment.service.onramp.onramper;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "finx.onramp.onramper")
public class OnramperProperties {
    private String environment = "sandbox";
    private String apiKey;
    private String v2PrivateKey;
    private String webhookSecret;
    private String baseUrl = "https://api.onramper.com";
    private String defaultFiat = "INR";
    private String defaultAsset = "USDC";

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getV2PrivateKey() { return v2PrivateKey; }
    public void setV2PrivateKey(String v2PrivateKey) { this.v2PrivateKey = v2PrivateKey; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getDefaultFiat() { return defaultFiat; }
    public void setDefaultFiat(String defaultFiat) { this.defaultFiat = defaultFiat; }
    public String getDefaultAsset() { return defaultAsset; }
    public void setDefaultAsset(String defaultAsset) { this.defaultAsset = defaultAsset; }
}
