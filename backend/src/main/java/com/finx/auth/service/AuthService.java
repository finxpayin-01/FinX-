package com.finx.auth.service;

import com.finx.audit.service.AuditService;
import com.finx.auth.dto.request.LoginRequest;
import com.finx.auth.dto.request.LogoutRequest;
import com.finx.auth.dto.request.RegisterRequest;
import com.finx.auth.dto.request.TokenRefreshRequest;
import com.finx.auth.dto.response.AuthResponse;
import com.finx.auth.dto.response.UserSummaryResponse;
import com.finx.auth.entity.RefreshToken;
import com.finx.auth.repository.RefreshTokenRepository;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.exception.BadRequestException;
import com.finx.exception.DuplicateResourceException;
import com.finx.exception.TokenRefreshException;
import com.finx.exception.UnauthorizedException;
import com.finx.security.jwt.JwtTokenProvider;
import com.finx.security.service.UserPrincipal;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.finx.auth.dto.request.GoogleOAuthRequest;
import com.finx.auth.dto.response.GoogleOAuthConfigResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuditService auditService;
    private final com.finx.auth.config.GoogleOAuthProperties googleOAuthProperties;
    private final com.finx.user.service.UserUidService userUidService;
    private final org.springframework.web.client.RestTemplate restTemplate;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider,
                       AuditService auditService) {
        this(userRepository, refreshTokenRepository, passwordEncoder, jwtTokenProvider, auditService, new com.finx.auth.config.GoogleOAuthProperties(), new com.finx.user.service.UserUidService(userRepository));
    }

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider,
                       AuditService auditService,
                       com.finx.auth.config.GoogleOAuthProperties googleOAuthProperties) {
        this(userRepository, refreshTokenRepository, passwordEncoder, jwtTokenProvider, auditService, googleOAuthProperties, new com.finx.user.service.UserUidService(userRepository));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider,
                       AuditService auditService,
                       com.finx.auth.config.GoogleOAuthProperties googleOAuthProperties,
                       com.finx.user.service.UserUidService userUidService) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.auditService = auditService;
        this.googleOAuthProperties = googleOAuthProperties != null ? googleOAuthProperties : new com.finx.auth.config.GoogleOAuthProperties();
        this.userUidService = userUidService != null ? userUidService : new com.finx.user.service.UserUidService(userRepository);
        this.restTemplate = new org.springframework.web.client.RestTemplate();
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (request.getRole() == Role.ADMIN) {
            throw new BadRequestException("ADMIN accounts cannot be registered publicly");
        }

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException("An account with email " + request.getEmail() + " already exists");
        }

        User user = new User(
                request.getName().trim(),
                request.getEmail().trim().toLowerCase(),
                passwordEncoder.encode(request.getPassword()),
                request.getRole(),
                UserStatus.ACTIVE
        );
        user.setUid(userUidService.generateUniqueUid());

        User savedUser = userRepository.saveAndFlush(user);

        // Issue tokens
        String accessToken = jwtTokenProvider.generateAccessToken(savedUser);
        String rawRefreshToken = jwtTokenProvider.generateRefreshTokenString();
        String tokenHash = jwtTokenProvider.hashToken(rawRefreshToken);
        Instant refreshExpiry = Instant.now().plusMillis(jwtTokenProvider.getRefreshTokenExpirationMs());

        RefreshToken refreshToken = new RefreshToken(savedUser, tokenHash, refreshExpiry);
        refreshTokenRepository.saveAndFlush(refreshToken);

        auditService.logEvent(
                savedUser.getId(),
                "USER_REGISTERED",
                "USER",
                savedUser.getId().toString(),
                "Registered with role " + savedUser.getRole()
        );

        return new AuthResponse(
                accessToken,
                rawRefreshToken,
                jwtTokenProvider.getAccessTokenExpirationMs() / 1000,
                UserSummaryResponse.fromEntity(savedUser)
        );
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail().trim().toLowerCase())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            auditService.logEvent(
                    user.getId(),
                    "FAILED_LOGIN_ATTEMPT",
                    "USER",
                    user.getId().toString(),
                    "Invalid password attempt for email: " + request.getEmail()
            );
            throw new BadCredentialsException("Invalid email or password");
        }

        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new UnauthorizedException("User account is suspended");
        }

        if (user.getUid() == null || user.getUid().isBlank()) {
            user.setUid(userUidService.generateUniqueUid());
            User saved = userRepository.saveAndFlush(user);
            if (saved != null) {
                user = saved;
            }
        }

        // Issue new token pair
        String accessToken = jwtTokenProvider.generateAccessToken(user);
        String rawRefreshToken = jwtTokenProvider.generateRefreshTokenString();
        String tokenHash = jwtTokenProvider.hashToken(rawRefreshToken);
        Instant refreshExpiry = Instant.now().plusMillis(jwtTokenProvider.getRefreshTokenExpirationMs());

        RefreshToken refreshToken = new RefreshToken(user, tokenHash, refreshExpiry);
        refreshTokenRepository.save(refreshToken);

        auditService.logEvent(
                user.getId(),
                "USER_LOGIN",
                "USER",
                user.getId().toString(),
                "User successfully logged in"
        );

        return new AuthResponse(
                accessToken,
                rawRefreshToken,
                jwtTokenProvider.getAccessTokenExpirationMs() / 1000,
                UserSummaryResponse.fromEntity(user)
        );
    }

    @Transactional
    public AuthResponse refreshToken(TokenRefreshRequest request) {
        String rawToken = request.getRefreshToken();
        String tokenHash = jwtTokenProvider.hashToken(rawToken);

        RefreshToken storedToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new TokenRefreshException(rawToken, "Invalid refresh token"));

        if (storedToken.isRevoked()) {
            // Security alert: Potential token reuse. Revoke all active tokens for this user.
            refreshTokenRepository.revokeAllActiveUserTokens(storedToken.getUser(), Instant.now());
            auditService.logEvent(
                    storedToken.getUser().getId(),
                    "REVOKED_TOKEN_REUSE_ATTEMPT",
                    "REFRESH_TOKEN",
                    storedToken.getId() != null ? storedToken.getId().toString() : "N/A",
                    "Attempted reuse of already revoked refresh token. All active user tokens revoked."
            );
            throw new TokenRefreshException(rawToken, "Refresh token has been revoked");
        }

        if (storedToken.isExpired()) {
            throw new TokenRefreshException(rawToken, "Refresh token has expired. Please login again.");
        }

        User user = storedToken.getUser();
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new UnauthorizedException("User account is suspended");
        }

        // Token rotation: Revoke current token
        storedToken.setRevokedAt(Instant.now());
        refreshTokenRepository.save(storedToken);

        // Issue new token pair
        String newAccessToken = jwtTokenProvider.generateAccessToken(user);
        String newRawRefreshToken = jwtTokenProvider.generateRefreshTokenString();
        String newTokenHash = jwtTokenProvider.hashToken(newRawRefreshToken);
        Instant newExpiry = Instant.now().plusMillis(jwtTokenProvider.getRefreshTokenExpirationMs());

        RefreshToken newRefreshToken = new RefreshToken(user, newTokenHash, newExpiry);
        RefreshToken savedNewToken = refreshTokenRepository.save(newRefreshToken);
        UUID auditId = (savedNewToken != null && savedNewToken.getId() != null)
                ? savedNewToken.getId()
                : newRefreshToken.getId();

        auditService.logEvent(
                user.getId(),
                "TOKEN_REFRESHED",
                "REFRESH_TOKEN",
                auditId != null ? auditId.toString() : "N/A",
                "Token rotated successfully"
        );

        return new AuthResponse(
                newAccessToken,
                newRawRefreshToken,
                jwtTokenProvider.getAccessTokenExpirationMs() / 1000,
                UserSummaryResponse.fromEntity(user)
        );
    }

    @Transactional
    public void logout(LogoutRequest request) {
        if (request.getRefreshToken() != null && !request.getRefreshToken().isBlank()) {
            String tokenHash = jwtTokenProvider.hashToken(request.getRefreshToken());
            refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(token -> {
                token.setRevokedAt(Instant.now());
                refreshTokenRepository.save(token);
                auditService.logEvent(
                        token.getUser().getId(),
                        "USER_LOGOUT",
                        "REFRESH_TOKEN",
                        token.getId() != null ? token.getId().toString() : "N/A",
                        "User logged out and revoked refresh token"
                );
            });
        }
    }

    @Transactional
    public UserSummaryResponse getCurrentUser(UserPrincipal principal) {
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new UnauthorizedException("User profile not found"));
        if (user.getUid() == null || user.getUid().isBlank()) {
            user.setUid(userUidService.generateUniqueUid());
            User saved = userRepository.saveAndFlush(user);
            if (saved != null) {
                user = saved;
            }
        }
        return UserSummaryResponse.fromEntity(user);
    }

    public GoogleOAuthConfigResponse getGoogleOAuthConfig() {
        String authUrl = googleOAuthProperties.isConfigured()
                ? googleOAuthProperties.buildBackendLoginUrl()
                : null;
        return new GoogleOAuthConfigResponse(
                googleOAuthProperties.isConfigured(),
                googleOAuthProperties.isConfigured() ? googleOAuthProperties.getClientId() : null,
                googleOAuthProperties.getRedirectUri(),
                authUrl
        );
    }

    @Transactional
    public AuthResponse authenticateWithGoogle(GoogleOAuthRequest request) {
        if (!googleOAuthProperties.isConfigured()) {
            throw new BadRequestException("Google OAuth is not configured on the server. Please set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET.");
        }

        if (request.getCode() == null || request.getCode().trim().isEmpty()) {
            throw new BadRequestException("Authorization code is required");
        }

        String redirectUri = (request.getRedirectUri() != null && !request.getRedirectUri().trim().isEmpty())
                ? request.getRedirectUri().trim()
                : googleOAuthProperties.getRedirectUri();

        // 1. Exchange authorization code with Google token endpoint
        HttpHeaders tokenHeaders = new HttpHeaders();
        tokenHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> tokenParams = new LinkedMultiValueMap<>();
        tokenParams.add("code", request.getCode().trim());
        tokenParams.add("client_id", googleOAuthProperties.getClientId());
        tokenParams.add("client_secret", googleOAuthProperties.getClientSecret());
        tokenParams.add("redirect_uri", redirectUri);
        tokenParams.add("grant_type", "authorization_code");

        HttpEntity<MultiValueMap<String, String>> tokenRequest = new HttpEntity<>(tokenParams, tokenHeaders);

        JsonNode tokenResponse;
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    "https://oauth2.googleapis.com/token",
                    tokenRequest,
                    String.class
            );
            tokenResponse = new ObjectMapper().readTree(response.getBody());
        } catch (Exception ex) {
            log.error("Google OAuth token exchange failed: {}", ex.getMessage());
            throw new BadRequestException("Invalid or expired Google authorization code: " + ex.getMessage());
        }

        if (tokenResponse == null || !tokenResponse.has("access_token")) {
            throw new BadRequestException("Failed to obtain access token from Google");
        }

        String googleAccessToken = tokenResponse.get("access_token").asText();

        // 2. Fetch userinfo from Google
        HttpHeaders userinfoHeaders = new HttpHeaders();
        userinfoHeaders.setBearerAuth(googleAccessToken);
        HttpEntity<Void> userinfoRequest = new HttpEntity<>(userinfoHeaders);

        JsonNode userinfo;
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    "https://www.googleapis.com/oauth2/v3/userinfo",
                    HttpMethod.GET,
                    userinfoRequest,
                    String.class
            );
            userinfo = new ObjectMapper().readTree(response.getBody());
        } catch (Exception ex) {
            log.error("Failed to fetch Google userinfo: {}", ex.getMessage());
            throw new BadRequestException("Failed to retrieve user profile from Google");
        }

        if (userinfo == null || !userinfo.has("email")) {
            throw new BadRequestException("Google account has no associated email");
        }

        boolean emailVerified = userinfo.has("email_verified") && userinfo.get("email_verified").asBoolean();
        if (!emailVerified) {
            throw new BadRequestException("Google account email is not verified");
        }

        String email = userinfo.get("email").asText().trim().toLowerCase();
        String name = userinfo.has("name") && !userinfo.get("name").asText().trim().isEmpty()
                ? userinfo.get("name").asText().trim()
                : email.split("@")[0];

        // 3. Find or Create User
        java.util.Optional<User> existingUserOpt = userRepository.findByEmail(email);
        User user;
        boolean isNewUser = false;

        if (existingUserOpt.isPresent()) {
            user = existingUserOpt.get();
            if (user.getStatus() == UserStatus.SUSPENDED) {
                throw new UnauthorizedException("User account is suspended");
            }
            if (user.getUid() == null || user.getUid().isBlank()) {
                user.setUid(userUidService.generateUniqueUid());
                user = userRepository.saveAndFlush(user);
            }
        } else {
            isNewUser = true;
            // Map role: BUYER for Corporate users, SELLER for Vendor users. Never allow ADMIN via public flow.
            Role assignedRole = Role.BUYER;
            if (request.getRole() == Role.SELLER) {
                assignedRole = Role.SELLER;
            } else if (request.getRole() == Role.ADMIN) {
                log.warn("Attempt to register ADMIN via Google OAuth denied. Defaulting to BUYER.");
                assignedRole = Role.BUYER;
            }

            // Generate cryptographically secure random password hash for OAuth users
            String dummyPassword = UUID.randomUUID().toString() + UUID.randomUUID().toString();
            user = new User(
                    name,
                    email,
                    passwordEncoder.encode(dummyPassword),
                    assignedRole,
                    UserStatus.ACTIVE
            );
            user.setUid(userUidService.generateUniqueUid());
            user = userRepository.saveAndFlush(user);
        }

        // 4. Issue standard FINX dual tokens
        String accessToken = jwtTokenProvider.generateAccessToken(user);
        String rawRefreshToken = jwtTokenProvider.generateRefreshTokenString();
        String tokenHash = jwtTokenProvider.hashToken(rawRefreshToken);
        Instant refreshExpiry = Instant.now().plusMillis(jwtTokenProvider.getRefreshTokenExpirationMs());

        RefreshToken refreshToken = new RefreshToken(user, tokenHash, refreshExpiry);
        refreshTokenRepository.saveAndFlush(refreshToken);

        auditService.logEvent(
                user.getId(),
                isNewUser ? "USER_REGISTERED_GOOGLE" : "USER_LOGIN_GOOGLE",
                "USER",
                user.getId().toString(),
                (isNewUser ? "Registered new account via Google OAuth with role " : "Logged in via Google OAuth with role ") + user.getRole()
        );

        return new AuthResponse(
                accessToken,
                rawRefreshToken,
                jwtTokenProvider.getAccessTokenExpirationMs() / 1000,
                UserSummaryResponse.fromEntity(user)
        );
    }
}
