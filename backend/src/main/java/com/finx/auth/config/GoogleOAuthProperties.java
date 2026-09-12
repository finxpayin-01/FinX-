package com.finx.auth.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "oauth.google")
public class GoogleOAuthProperties {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthProperties.class);

    private String clientId;
    private String clientSecret;
    private String redirectUri = "http://localhost:8080/api/auth/google/callback";
    private String frontendRedirectUrl = "http://localhost:3000/auth/callback/google";

    @PostConstruct
    public void validateConfiguration() {
        // Dynamic resolution for Render and Production environments
        String renderExternalUrl = System.getenv("RENDER_EXTERNAL_URL");
        if (renderExternalUrl != null && !renderExternalUrl.trim().isEmpty()) {
            String baseUrl = renderExternalUrl.trim().replaceAll("/+$", "");
            // If redirectUri is default localhost, override with Render's public URL
            if (this.redirectUri == null || this.redirectUri.trim().isEmpty() || this.redirectUri.contains("localhost")) {
                this.redirectUri = baseUrl + "/api/auth/google/callback";
                log.info("[OAUTH CONFIGURATION] Automatically resolved redirectUri from RENDER_EXTERNAL_URL: {}", this.redirectUri);
            }
        }

        String frontendUrl = System.getenv("FRONTEND_URL");
        if (frontendUrl != null && !frontendUrl.trim().isEmpty()) {
            String baseFrontend = frontendUrl.trim().replaceAll("/+$", "");
            if (this.frontendRedirectUrl == null || this.frontendRedirectUrl.trim().isEmpty() || this.frontendRedirectUrl.contains("localhost")) {
                this.frontendRedirectUrl = baseFrontend + "/auth/callback/google";
                log.info("[OAUTH CONFIGURATION] Automatically resolved frontendRedirectUrl from FRONTEND_URL: {}", this.frontendRedirectUrl);
            }
        }

        String activeProfile = System.getProperty("spring.profiles.active", System.getenv("SPRING_PROFILES_ACTIVE"));
        if (activeProfile != null && (activeProfile.contains("prod") || activeProfile.contains("production"))) {
            if (this.redirectUri == null || this.redirectUri.trim().isEmpty() || this.redirectUri.contains("localhost") || 
                (this.redirectUri.contains("finx-backend.onrender.com") && !this.redirectUri.contains("-vq5b"))) {
                this.redirectUri = "https://finx-backend-vq5b.onrender.com/api/auth/google/callback";
                log.info("[OAUTH CONFIGURATION] Production profile active: resolved redirectUri to canonical Render URL: {}", this.redirectUri);
            }
            if (this.frontendRedirectUrl == null || this.frontendRedirectUrl.trim().isEmpty() || this.frontendRedirectUrl.contains("localhost") ||
                (this.frontendRedirectUrl.contains("finx-frontend.onrender.com") && !this.frontendRedirectUrl.contains("-i9an"))) {
                this.frontendRedirectUrl = "https://finx-frontend-i9an.onrender.com/auth/callback/google";
                log.info("[OAUTH CONFIGURATION] Production profile active: resolved frontendRedirectUrl to Render: {}", this.frontendRedirectUrl);
            }
        }

        if (!isConfigured()) {
            log.warn("================================================================================");
            log.warn("[OAUTH CONFIGURATION] Google OAuth 2.0 is NOT configured on this server.");
            log.warn("Missing required environment variables: GOOGLE_CLIENT_ID and/or GOOGLE_CLIENT_SECRET.");
            log.warn("Google Sign-In will prompt users to configure credentials or sign in with email.");
            log.warn("Expected Redirect URI: {}", redirectUri);
            log.warn("================================================================================");
        } else {
            log.info("================================================================================");
            log.info("[OAUTH CONFIGURATION] Google OAuth 2.0 is CONFIGURED and ACTIVE.");
            String maskedId = clientId.length() > 8 ? clientId.substring(0, 8) + "..." : "[CONFIGURED]";
            log.info("Client ID: {}", maskedId);
            log.info("Client Secret: [CONFIGURED - NEVER LOGGED]");
            log.info("Backend Redirect URI: {}", redirectUri);
            log.info("Frontend Redirect URL: {}", frontendRedirectUrl);
            log.info("================================================================================");
        }
    }

    public boolean isConfigured() {
        return clientId != null && !clientId.trim().isEmpty() &&
               clientSecret != null && !clientSecret.trim().isEmpty();
    }

    public String buildAuthorizationUrl() {
        return buildAuthorizationUrl(null);
    }

    public String buildAuthorizationUrl(String state) {
        if (!isConfigured()) {
            return null;
        }
        try {
            String url = "https://accounts.google.com/o/oauth2/v2/auth" +
                    "?client_id=" + java.net.URLEncoder.encode(clientId, java.nio.charset.StandardCharsets.UTF_8) +
                    "&redirect_uri=" + java.net.URLEncoder.encode(redirectUri, java.nio.charset.StandardCharsets.UTF_8) +
                    "&response_type=code" +
                    "&scope=" + java.net.URLEncoder.encode("openid email profile", java.nio.charset.StandardCharsets.UTF_8) +
                    "&access_type=offline" +
                    "&prompt=select_account";
            if (state != null && !state.trim().isEmpty()) {
                url += "&state=" + java.net.URLEncoder.encode(state.trim(), java.nio.charset.StandardCharsets.UTF_8);
            }
            return url;
        } catch (Exception e) {
            log.error("Failed to encode Google authorization URL", e);
            return null;
        }
    }

    public String buildBackendLoginUrl() {
        if (redirectUri != null && redirectUri.contains("/api/auth/google/callback")) {
            return redirectUri.replace("/api/auth/google/callback", "/api/auth/google/login");
        }
        return redirectUri != null ? redirectUri.replaceAll("/+$", "") + "/login" : "http://localhost:8080/api/auth/google/login";
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

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        if (redirectUri != null) {
            this.redirectUri = redirectUri.trim().replaceAll("/+$", "");
        } else {
            this.redirectUri = null;
        }
    }

    public String getFrontendRedirectUrl() {
        return frontendRedirectUrl;
    }

    public void setFrontendRedirectUrl(String frontendRedirectUrl) {
        if (frontendRedirectUrl != null) {
            this.frontendRedirectUrl = frontendRedirectUrl.trim().replaceAll("/+$", "");
        } else {
            this.frontendRedirectUrl = null;
        }
    }
}
