package com.finx.banking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Type-safe configuration properties for regulated banking partner adapters.
 * Bound to prefix 'finx.banking.partner'.
 * Strictly encapsulates secret boundaries and validates production-readiness invariants.
 */
@Component
@ConfigurationProperties(prefix = "finx.banking.partner")
public class BankingPartnerProperties {

    private boolean enabled = false;
    private String providerName = "";
    private String baseUrl = "";
    private String clientId = "";
    private String clientSecret = "";
    private boolean sandbox = true;
    private boolean productionExecutionEnabled = false;
    private long connectTimeoutMs = 5000;
    private long readTimeoutMs = 15000;

    private MtlsProperties mtls = new MtlsProperties();
    private SigningProperties signing = new SigningProperties();
    private WebhookProperties webhook = new WebhookProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProviderName() {
        return providerName;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public boolean isSandbox() {
        return sandbox;
    }

    public void setSandbox(boolean sandbox) {
        this.sandbox = sandbox;
    }

    public boolean isProductionExecutionEnabled() {
        return productionExecutionEnabled;
    }

    public void setProductionExecutionEnabled(boolean productionExecutionEnabled) {
        this.productionExecutionEnabled = productionExecutionEnabled;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(long readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public MtlsProperties getMtls() {
        return mtls;
    }

    public void setMtls(MtlsProperties mtls) {
        this.mtls = mtls;
    }

    public SigningProperties getSigning() {
        return signing;
    }

    public void setSigning(SigningProperties signing) {
        this.signing = signing;
    }

    public WebhookProperties getWebhook() {
        return webhook;
    }

    public void setWebhook(WebhookProperties webhook) {
        this.webhook = webhook;
    }

    /**
     * Validates partner configuration invariants when partner provider mode is activated.
     * Fails fast during application context startup if security or transport invariants are violated.
     */
    public void validateForPartnerMode() {
        if (providerName == null || providerName.trim().isBlank()) {
            throw new IllegalStateException("PARTNER_CONFIG_INVALID: finx.banking.partner.provider-name must not be blank in partner mode");
        }
        if (baseUrl == null || baseUrl.trim().isBlank()) {
            throw new IllegalStateException("PARTNER_CONFIG_INVALID: finx.banking.partner.base-url must not be blank in partner mode");
        }
        if (clientId == null || clientId.trim().isBlank()) {
            throw new IllegalStateException("PARTNER_CONFIG_INVALID: finx.banking.partner.client-id must not be blank in partner mode");
        }
        if (connectTimeoutMs <= 0) {
            throw new IllegalStateException("PARTNER_CONFIG_INVALID: finx.banking.partner.connect-timeout-ms must be positive");
        }
        if (readTimeoutMs <= 0) {
            throw new IllegalStateException("PARTNER_CONFIG_INVALID: finx.banking.partner.read-timeout-ms must be positive");
        }

        // Mutual TLS is mandatory for all regulated partner banking communication
        if (mtls == null || !mtls.isEnabled()) {
            throw new IllegalStateException("PARTNER_CONFIG_INVALID: finx.banking.partner.mtls.enabled must be true in partner mode");
        }
        if (mtls.getKeyStorePath() == null || mtls.getKeyStorePath().trim().isBlank()) {
            throw new IllegalStateException("PARTNER_CONFIG_INVALID: finx.banking.partner.mtls.key-store-path must not be blank");
        }
        if (mtls.getKeyStorePassword() == null || mtls.getKeyStorePassword().trim().isBlank()) {
            throw new IllegalStateException("PARTNER_CONFIG_INVALID: finx.banking.partner.mtls.key-store-password must not be blank");
        }
        if (mtls.getTrustStorePath() == null || mtls.getTrustStorePath().trim().isBlank()) {
            throw new IllegalStateException("PARTNER_CONFIG_INVALID: finx.banking.partner.mtls.trust-store-path must not be blank");
        }
        if (mtls.getTrustStorePassword() == null || mtls.getTrustStorePassword().trim().isBlank()) {
            throw new IllegalStateException("PARTNER_CONFIG_INVALID: finx.banking.partner.mtls.trust-store-password must not be blank");
        }

        // Strict E10 boundary: Sandbox must be true, production execution must be false
        if (!sandbox) {
            throw new IllegalStateException("PARTNER_CONFIG_INVALID: finx.banking.partner.sandbox must be true in E10");
        }
        if (productionExecutionEnabled) {
            throw new IllegalStateException("PARTNER_CONFIG_INVALID: finx.banking.partner.production-execution-enabled must be false in E10. Real money movement is strictly disabled.");
        }
    }

    public static class MtlsProperties {
        private boolean enabled = false;
        private String keyStorePath = "";
        private String keyStorePassword = "";
        private String keyStoreType = "PKCS12";
        private String trustStorePath = "";
        private String trustStorePassword = "";
        private String trustStoreType = "PKCS12";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getKeyStorePath() {
            return keyStorePath;
        }

        public void setKeyStorePath(String keyStorePath) {
            this.keyStorePath = keyStorePath;
        }

        public String getKeyStorePassword() {
            return keyStorePassword;
        }

        public void setKeyStorePassword(String keyStorePassword) {
            this.keyStorePassword = keyStorePassword;
        }

        public String getKeyStoreType() {
            return keyStoreType;
        }

        public void setKeyStoreType(String keyStoreType) {
            this.keyStoreType = keyStoreType;
        }

        public String getTrustStorePath() {
            return trustStorePath;
        }

        public void setTrustStorePath(String trustStorePath) {
            this.trustStorePath = trustStorePath;
        }

        public String getTrustStorePassword() {
            return trustStorePassword;
        }

        public void setTrustStorePassword(String trustStorePassword) {
            this.trustStorePassword = trustStorePassword;
        }

        public String getTrustStoreType() {
            return trustStoreType;
        }

        public void setTrustStoreType(String trustStoreType) {
            this.trustStoreType = trustStoreType;
        }

        @Override
        public String toString() {
            return "MtlsProperties{" +
                    "enabled=" + enabled +
                    ", keyStorePath='" + keyStorePath + '\'' +
                    ", keyStorePassword='***'" +
                    ", keyStoreType='" + keyStoreType + '\'' +
                    ", trustStorePath='" + trustStorePath + '\'' +
                    ", trustStorePassword='***'" +
                    ", trustStoreType='" + trustStoreType + '\'' +
                    '}';
        }
    }

    public static class SigningProperties {
        private boolean enabled = false;
        private String algorithm = "HMAC-SHA256";
        private String keyId = "";
        private String privateKeyPath = "";
        private String secret = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getAlgorithm() {
            return algorithm;
        }

        public void setAlgorithm(String algorithm) {
            this.algorithm = algorithm;
        }

        public String getKeyId() {
            return keyId;
        }

        public void setKeyId(String keyId) {
            this.keyId = keyId;
        }

        public String getPrivateKeyPath() {
            return privateKeyPath;
        }

        public void setPrivateKeyPath(String privateKeyPath) {
            this.privateKeyPath = privateKeyPath;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        @Override
        public String toString() {
            return "SigningProperties{" +
                    "enabled=" + enabled +
                    ", algorithm='" + algorithm + '\'' +
                    ", keyId='" + keyId + '\'' +
                    ", privateKeyPath='" + privateKeyPath + '\'' +
                    ", secret='***'" +
                    '}';
        }
    }

    public static class WebhookProperties {
        private String signatureAlgorithm = "HMAC-SHA256";
        private String secret = "";
        private String publicKeyPath = "";

        public String getSignatureAlgorithm() {
            return signatureAlgorithm;
        }

        public void setSignatureAlgorithm(String signatureAlgorithm) {
            this.signatureAlgorithm = signatureAlgorithm;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getPublicKeyPath() {
            return publicKeyPath;
        }

        public void setPublicKeyPath(String publicKeyPath) {
            this.publicKeyPath = publicKeyPath;
        }

        @Override
        public String toString() {
            return "WebhookProperties{" +
                    "signatureAlgorithm='" + signatureAlgorithm + '\'' +
                    ", secret='***'" +
                    ", publicKeyPath='" + publicKeyPath + '\'' +
                    '}';
        }
    }

    @Override
    public String toString() {
        return "BankingPartnerProperties{" +
                "enabled=" + enabled +
                ", providerName='" + providerName + '\'' +
                ", baseUrl='" + baseUrl + '\'' +
                ", clientId='" + clientId + '\'' +
                ", clientSecret='***'" +
                ", sandbox=" + sandbox +
                ", productionExecutionEnabled=" + productionExecutionEnabled +
                ", connectTimeoutMs=" + connectTimeoutMs +
                ", readTimeoutMs=" + readTimeoutMs +
                ", mtls=" + mtls +
                ", signing=" + signing +
                ", webhook=" + webhook +
                '}';
    }
}
