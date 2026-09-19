package com.finx.auth.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleOAuthPropertiesTest {

    private String originalProfile;

    @BeforeEach
    void setUp() {
        originalProfile = System.getProperty("spring.profiles.active");
    }

    @AfterEach
    void tearDown() {
        if (originalProfile != null) {
            System.setProperty("spring.profiles.active", originalProfile);
        } else {
            System.clearProperty("spring.profiles.active");
        }
    }

    @Test
    void shouldDefaultToLocalhostWhenNotProd() {
        System.clearProperty("spring.profiles.active");
        GoogleOAuthProperties props = new GoogleOAuthProperties();
        props.validateConfiguration();

        assertThat(props.getFrontendRedirectUrl()).isEqualTo("http://localhost:3000/auth/callback/google");
        assertThat(props.getRedirectUri()).isEqualTo("http://localhost:8080/api/auth/google/callback");
    }

    @Test
    void shouldResolveCanonicalFrontendInProduction() {
        System.setProperty("spring.profiles.active", "prod");
        GoogleOAuthProperties props = new GoogleOAuthProperties();
        props.validateConfiguration();

        assertThat(props.getFrontendRedirectUrl())
                .isEqualTo("https://finx-frontend.onrender.com/auth/callback/google");
        assertThat(props.getRedirectUri())
                .isEqualTo("https://finx-backend-vq5b.onrender.com/api/auth/google/callback");
    }

    @Test
    void shouldSanitizeObsoleteI9anDomainInProduction() {
        System.setProperty("spring.profiles.active", "prod");
        GoogleOAuthProperties props = new GoogleOAuthProperties();
        props.setFrontendRedirectUrl("https://finx-frontend-i9an.onrender.com/auth/callback/google");
        props.validateConfiguration();

        assertThat(props.getFrontendRedirectUrl())
                .isEqualTo("https://finx-frontend.onrender.com/auth/callback/google");
    }

    @Test
    void shouldSanitizeObsolete7ffvDomainInProduction() {
        System.setProperty("spring.profiles.active", "prod");
        GoogleOAuthProperties props = new GoogleOAuthProperties();
        props.setFrontendRedirectUrl("https://finx-frontend-7ffv.onrender.com/auth/callback/google");
        props.validateConfiguration();

        assertThat(props.getFrontendRedirectUrl())
                .isEqualTo("https://finx-frontend.onrender.com/auth/callback/google");
    }

    @Test
    void shouldRetain19anDomainInProduction() {
        System.setProperty("spring.profiles.active", "prod");
        GoogleOAuthProperties props = new GoogleOAuthProperties();
        props.setFrontendRedirectUrl("https://finx-frontend-19an.onrender.com/auth/callback/google");
        props.validateConfiguration();

        assertThat(props.getFrontendRedirectUrl())
                .isEqualTo("https://finx-frontend-19an.onrender.com/auth/callback/google");
    }

    @Test
    void shouldRetainExplicitCustomDomainInProduction() {
        System.setProperty("spring.profiles.active", "prod");
        GoogleOAuthProperties props = new GoogleOAuthProperties();
        props.setFrontendRedirectUrl("https://custom-domain.com/auth/callback/google");
        props.validateConfiguration();

        assertThat(props.getFrontendRedirectUrl())
                .isEqualTo("https://custom-domain.com/auth/callback/google");
    }
}
