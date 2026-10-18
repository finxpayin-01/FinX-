package com.finx.auth;

import com.finx.audit.service.AuditService;
import com.finx.auth.config.GoogleOAuthProperties;
import com.finx.auth.controller.AuthController;
import com.finx.auth.dto.response.GoogleOAuthConfigResponse;
import com.finx.auth.repository.RefreshTokenRepository;
import com.finx.auth.service.AuthService;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.common.response.ApiResponse;
import com.finx.security.jwt.JwtTokenProvider;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import com.finx.user.service.PmIdService;
import com.finx.user.service.UserUidService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoogleOAuthFlowTest {

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditService auditService;
    @Mock private UserUidService userUidService;
    @Mock private PmIdService pmIdService;

    private GoogleOAuthProperties googleOAuthProperties;
    private AuthService authService;
    private AuthController authController;

    private final String testSecret = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(testSecret, 900000L, 604800000L);
        googleOAuthProperties = new GoogleOAuthProperties();
        googleOAuthProperties.setClientId("test-client.apps.googleusercontent.com");
        googleOAuthProperties.setClientSecret("test-secret-value");
        googleOAuthProperties.setRedirectUri("http://localhost:8080/api/auth/google/callback");
        googleOAuthProperties.setFrontendRedirectUrl("http://localhost:3000/auth/callback/google");
        googleOAuthProperties.validateConfiguration();

        authService = new AuthService(
                userRepository,
                refreshTokenRepository,
                passwordEncoder,
                jwtTokenProvider,
                auditService,
                googleOAuthProperties,
                userUidService,
                pmIdService
        );

        authController = new AuthController(authService, googleOAuthProperties);
    }

    @Test
    @DisplayName("PHASE 10: Config endpoint returns configured=true, clientId, redirectUri, authUrl without leaking secret")
    void testConfigEndpoint_Configured() {
        ResponseEntity<ApiResponse<GoogleOAuthConfigResponse>> response = authController.getGoogleOAuthConfig();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        GoogleOAuthConfigResponse data = response.getBody().getData();
        assertThat(data.isConfigured()).isTrue();
        assertThat(data.getClientId()).isEqualTo("test-client.apps.googleusercontent.com");
        assertThat(data.getRedirectUri()).isEqualTo("http://localhost:8080/api/auth/google/callback");
        assertThat(data.getAuthUrl()).isEqualTo("http://localhost:8080/api/auth/google/login");
    }

    @Test
    @DisplayName("PHASE 10: Config endpoint returns configured=false with null details when credentials missing")
    void testConfigEndpoint_Unconfigured() {
        googleOAuthProperties.setClientId(null);
        googleOAuthProperties.setClientSecret(null);

        ResponseEntity<ApiResponse<GoogleOAuthConfigResponse>> response = authController.getGoogleOAuthConfig();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        GoogleOAuthConfigResponse data = response.getBody().getData();
        assertThat(data.isConfigured()).isFalse();
        assertThat(data.getClientId()).isNull();
        assertThat(data.getRedirectUri()).isNull();
        assertThat(data.getAuthUrl()).isNull();
    }

    @Test
    @DisplayName("PHASE 11: Login endpoint fails-closed with 400 when OAuth is not configured")
    void testLoginEndpoint_FailsClosedWhenUnconfigured() throws IOException {
        googleOAuthProperties.setClientId(null);
        googleOAuthProperties.setClientSecret(null);

        MockHttpServletResponse response = new MockHttpServletResponse();
        authController.redirectToGoogleOAuth(response);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getErrorMessage()).contains("Google OAuth is not configured");
    }

    @Test
    @DisplayName("PHASE 11 & 12: Login endpoint generates state, sets HttpOnly SameSite=Lax cookie, and redirects to accounts.google.com")
    void testLoginEndpoint_SuccessRedirectAndStateCookie() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        authController.redirectToGoogleOAuth(response);

        assertThat(response.getStatus()).isEqualTo(302);
        String location = response.getRedirectedUrl();
        assertThat(location).isNotNull();
        assertThat(location).startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
        assertThat(location).contains("client_id=test-client.apps.googleusercontent.com");
        assertThat(location).contains("redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Fauth%2Fgoogle%2Fcallback");

        // Verify state cookie
        String setCookie = response.getHeader("Set-Cookie");
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains("oauth_state=");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("SameSite=Lax");
        assertThat(setCookie).contains("Path=/");
    }

    @Test
    @DisplayName("PHASE 12: Callback rejects missing state cookie with invalid_state error redirect")
    void testCallback_MissingStateCookie_Rejects() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        authController.handleGoogleCallback("some_auth_code", "some_state_param", null, null, request, response);

        assertThat(response.getStatus()).isEqualTo(302);
        String redirectedUrl = response.getRedirectedUrl();
        assertThat(redirectedUrl).isNotNull();
        assertThat(redirectedUrl).contains("http://localhost:3000/auth/callback/google?error=invalid_state");
    }

    @Test
    @DisplayName("PHASE 12: Callback rejects mismatched state with invalid_state error redirect")
    void testCallback_MismatchedState_Rejects() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("oauth_state", "expected_cookie_state"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        authController.handleGoogleCallback("some_auth_code", "tampered_request_state", null, null, request, response);

        assertThat(response.getStatus()).isEqualTo(302);
        String redirectedUrl = response.getRedirectedUrl();
        assertThat(redirectedUrl).isNotNull();
        assertThat(redirectedUrl).contains("http://localhost:3000/auth/callback/google?error=invalid_state");
    }

    @Test
    @DisplayName("PHASE 13: Callback redirects with error when Google reports error")
    void testCallback_GoogleErrorParam_RedirectsToFrontend() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        authController.handleGoogleCallback(null, null, "access_denied", "User denied access", request, response);

        assertThat(response.getStatus()).isEqualTo(302);
        String redirectedUrl = response.getRedirectedUrl();
        assertThat(redirectedUrl).isNotNull();
        assertThat(redirectedUrl).contains("error=access_denied");
        assertThat(redirectedUrl).contains("error_description=" + URLDecoder.decode("User+denied+access", StandardCharsets.UTF_8).replace(" ", "+"));
    }

    @Test
    @DisplayName("PHASE 13: Callback redirects with missing_code when code parameter is absent")
    void testCallback_MissingCode_RedirectsToFrontend() throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        authController.handleGoogleCallback("", "valid_state", null, null, request, response);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).contains("error=missing_code");
    }
}
