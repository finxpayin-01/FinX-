package com.finx.auth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleOAuthPropertiesTest {

    @Test
    @DisplayName("Should default to canonical localhost URLs when environment is not set")
    void shouldDefaultToLocalhostWhenUnset() {
        GoogleOAuthProperties props = new GoogleOAuthProperties();
        props.validateConfiguration();

        assertThat(props.getFrontendRedirectUrl()).isEqualTo("http://localhost:3000/auth/callback/google");
        assertThat(props.getRedirectUri()).isEqualTo("http://localhost:8080/api/auth/google/callback");
    }

    @Test
    @DisplayName("Should respect explicitly configured redirectUri and frontendRedirectUrl")
    void shouldRespectConfiguredUrls() {
        GoogleOAuthProperties props = new GoogleOAuthProperties();
        props.setRedirectUri("https://staging-api.example.com/api/auth/google/callback");
        props.setFrontendRedirectUrl("https://staging-app.example.com/auth/callback/google");
        props.validateConfiguration();

        assertThat(props.getRedirectUri()).isEqualTo("https://staging-api.example.com/api/auth/google/callback");
        assertThat(props.getFrontendRedirectUrl()).isEqualTo("https://staging-app.example.com/auth/callback/google");
    }

    @Test
    @DisplayName("isConfigured() returns false when clientId or clientSecret are missing")
    void testIsConfigured_FalseWhenMissing() {
        GoogleOAuthProperties props = new GoogleOAuthProperties();
        props.setClientId(null);
        props.setClientSecret(null);
        assertThat(props.isConfigured()).isFalse();

        props.setClientId("some-client-id");
        props.setClientSecret("");
        assertThat(props.isConfigured()).isFalse();

        props.setClientId("   ");
        props.setClientSecret("some-secret");
        assertThat(props.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("isConfigured() returns true when both clientId and clientSecret are set")
    void testIsConfigured_TrueWhenPresent() {
        GoogleOAuthProperties props = new GoogleOAuthProperties();
        props.setClientId("valid-client-id.apps.googleusercontent.com");
        props.setClientSecret("valid-client-secret-12345");
        assertThat(props.isConfigured()).isTrue();
    }

    @Test
    @DisplayName("buildAuthorizationUrl contains exact redirect_uri and state")
    void testBuildAuthorizationUrl_ExactEncoding() {
        GoogleOAuthProperties props = new GoogleOAuthProperties();
        props.setClientId("test-client.apps.googleusercontent.com");
        props.setClientSecret("test-secret");
        props.setRedirectUri("http://localhost:8080/api/auth/google/callback");
        props.validateConfiguration();

        String state = "secure-random-state-123";
        String authUrl = props.buildAuthorizationUrl(state);

        assertThat(authUrl).isNotNull();
        assertThat(authUrl).startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
        assertThat(authUrl).contains("client_id=test-client.apps.googleusercontent.com");
        assertThat(authUrl).contains("redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Fauth%2Fgoogle%2Fcallback");
        assertThat(authUrl).contains("response_type=code");
        assertThat(authUrl).contains("scope=openid+email+profile");
        assertThat(authUrl).contains("state=secure-random-state-123");
        assertThat(authUrl).contains("access_type=offline");
        assertThat(authUrl).contains("prompt=select_account");
    }

    @Test
    @DisplayName("buildBackendLoginUrl resolves matching login path")
    void testBuildBackendLoginUrl() {
        GoogleOAuthProperties props = new GoogleOAuthProperties();
        props.setRedirectUri("http://localhost:8080/api/auth/google/callback");
        props.validateConfiguration();

        assertThat(props.buildBackendLoginUrl()).isEqualTo("http://localhost:8080/api/auth/google/login");

        props.setRedirectUri("https://backend.example.com/api/auth/google/callback");
        props.validateConfiguration();
        assertThat(props.buildBackendLoginUrl()).isEqualTo("https://backend.example.com/api/auth/google/login");
    }
}
