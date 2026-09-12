package com.finx.auth;

import com.finx.audit.service.AuditService;
import com.finx.auth.dto.request.LoginRequest;
import com.finx.auth.dto.request.LogoutRequest;
import com.finx.auth.dto.request.RegisterRequest;
import com.finx.auth.dto.request.TokenRefreshRequest;
import com.finx.auth.dto.response.AuthResponse;
import com.finx.auth.entity.RefreshToken;
import com.finx.auth.repository.RefreshTokenRepository;
import com.finx.auth.service.AuthService;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.exception.BadRequestException;
import com.finx.exception.DuplicateResourceException;
import com.finx.exception.TokenRefreshException;
import com.finx.exception.UnauthorizedException;
import com.finx.security.jwt.JwtTokenProvider;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuditService auditService;

    private JwtTokenProvider jwtTokenProvider;
    private AuthService authService;

    private User sampleUser;
    private final String rawPassword = "SecurePassword123!";
    private final String encodedPassword = "$2a$12$encodedHashSample";
    private final String testSecret = "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970";

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(testSecret, 900000L, 604800000L);
        authService = new AuthService(userRepository, refreshTokenRepository, passwordEncoder, jwtTokenProvider, auditService);

        sampleUser = new User("Alice Client", "alice@example.com", encodedPassword, Role.BUYER, UserStatus.ACTIVE);
        sampleUser.setId(UUID.randomUUID());
        sampleUser.setUid("USR-12345678");
    }

    @Test
    @DisplayName("Should successfully register a BUYER")
    void testRegister_Success_Buyer() {
        RegisterRequest request = new RegisterRequest("Alice Client", "alice@example.com", rawPassword, Role.BUYER);

        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode(rawPassword)).thenReturn(encodedPassword);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(sampleUser);

        AuthResponse response = authService.register(request);

        assertThat(response).isNotNull();
        assertThat(response.getAccessToken()).isNotBlank();
        assertThat(response.getRefreshToken()).isNotBlank();
        assertThat(response.getUser().getEmail()).isEqualTo("alice@example.com");
        assertThat(response.getUser().getRole()).isEqualTo(Role.BUYER);

        verify(userRepository).saveAndFlush(any(User.class));
        verify(refreshTokenRepository).saveAndFlush(any(RefreshToken.class));
        verify(auditService).logEvent(any(), eq("USER_REGISTERED"), eq("USER"), any(), any());
    }

    @Test
    @DisplayName("Should successfully register a SELLER")
    void testRegister_Success_Seller() {
        RegisterRequest request = new RegisterRequest("Bob Provider", "bob@example.com", rawPassword, Role.SELLER);
        User sellerUser = new User("Bob Provider", "bob@example.com", encodedPassword, Role.SELLER, UserStatus.ACTIVE);
        sellerUser.setId(UUID.randomUUID());

        when(userRepository.existsByEmail("bob@example.com")).thenReturn(false);
        when(passwordEncoder.encode(rawPassword)).thenReturn(encodedPassword);
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(sellerUser);

        AuthResponse response = authService.register(request);

        assertThat(response).isNotNull();
        assertThat(response.getUser().getRole()).isEqualTo(Role.SELLER);
    }

    @Test
    @DisplayName("Should reject public registration for ADMIN role")
    void testRegister_AdminRole_ThrowsBadRequestException() {
        RegisterRequest request = new RegisterRequest("Admin Root", "admin@finx.com", rawPassword, Role.ADMIN);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("ADMIN accounts cannot be registered publicly");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should reject duplicate email registration")
    void testRegister_DuplicateEmail_ThrowsDuplicateResourceException() {
        RegisterRequest request = new RegisterRequest("Alice Client", "alice@example.com", rawPassword, Role.BUYER);
        when(userRepository.existsByEmail("alice@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("already exists");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should successfully login with valid credentials")
    void testLogin_Success() {
        LoginRequest request = new LoginRequest("alice@example.com", rawPassword);

        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(sampleUser));
        when(passwordEncoder.matches(rawPassword, encodedPassword)).thenReturn(true);

        AuthResponse response = authService.login(request);

        assertThat(response).isNotNull();
        assertThat(response.getAccessToken()).isNotBlank();
        assertThat(response.getRefreshToken()).isNotBlank();
        verify(auditService).logEvent(sampleUser.getId(), "USER_LOGIN", "USER", sampleUser.getId().toString(), "User successfully logged in");
    }

    @Test
    @DisplayName("Should reject login with invalid password")
    void testLogin_InvalidPassword_ThrowsBadCredentialsException() {
        LoginRequest request = new LoginRequest("alice@example.com", "WrongPassword!");

        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(sampleUser));
        when(passwordEncoder.matches("WrongPassword!", encodedPassword)).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Invalid email or password");
    }

    @Test
    @DisplayName("Should reject login for suspended user")
    void testLogin_SuspendedUser_ThrowsUnauthorizedException() {
        sampleUser.setStatus(UserStatus.SUSPENDED);
        LoginRequest request = new LoginRequest("alice@example.com", rawPassword);

        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(sampleUser));
        when(passwordEncoder.matches(rawPassword, encodedPassword)).thenReturn(true);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("User account is suspended");
    }

    @Test
    @DisplayName("Should rotate tokens on valid refresh request")
    void testRefreshToken_Success_RotatesToken() {
        String rawToken = jwtTokenProvider.generateRefreshTokenString();
        String tokenHash = jwtTokenProvider.hashToken(rawToken);
        RefreshToken existingToken = new RefreshToken(sampleUser, tokenHash, Instant.now().plusSeconds(3600));
        existingToken.setId(UUID.randomUUID());

        TokenRefreshRequest request = new TokenRefreshRequest(rawToken);

        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(existingToken));

        AuthResponse response = authService.refreshToken(request);

        assertThat(response).isNotNull();
        assertThat(response.getAccessToken()).isNotBlank();
        assertThat(response.getRefreshToken()).isNotBlank();
        assertThat(response.getRefreshToken()).isNotEqualTo(rawToken);
        assertThat(existingToken.isRevoked()).isTrue();

        verify(refreshTokenRepository, times(2)).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("Should reject refresh request if token is already revoked")
    void testRefreshToken_RevokedToken_ThrowsTokenRefreshException() {
        String rawToken = jwtTokenProvider.generateRefreshTokenString();
        String tokenHash = jwtTokenProvider.hashToken(rawToken);
        RefreshToken existingToken = new RefreshToken(sampleUser, tokenHash, Instant.now().plusSeconds(3600));
        existingToken.setRevokedAt(Instant.now().minusSeconds(100));

        TokenRefreshRequest request = new TokenRefreshRequest(rawToken);

        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(existingToken));

        assertThatThrownBy(() -> authService.refreshToken(request))
                .isInstanceOf(TokenRefreshException.class)
                .hasMessageContaining("Refresh token has been revoked");

        verify(refreshTokenRepository).revokeAllActiveUserTokens(eq(sampleUser), any(Instant.class));
    }

    @Test
    @DisplayName("Should reject refresh request if token has expired")
    void testRefreshToken_ExpiredToken_ThrowsTokenRefreshException() {
        String rawToken = jwtTokenProvider.generateRefreshTokenString();
        String tokenHash = jwtTokenProvider.hashToken(rawToken);
        RefreshToken existingToken = new RefreshToken(sampleUser, tokenHash, Instant.now().minusSeconds(3600));

        TokenRefreshRequest request = new TokenRefreshRequest(rawToken);

        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(existingToken));

        assertThatThrownBy(() -> authService.refreshToken(request))
                .isInstanceOf(TokenRefreshException.class)
                .hasMessageContaining("Refresh token has expired");
    }

    @Test
    @DisplayName("Should successfully logout and revoke token")
    void testLogout_RevokesToken() {
        String rawToken = jwtTokenProvider.generateRefreshTokenString();
        String hash = jwtTokenProvider.hashToken(rawToken);
        RefreshToken activeToken = new RefreshToken(sampleUser, hash, Instant.now().plusSeconds(3600));

        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(activeToken));

        authService.logout(new LogoutRequest(rawToken));

        assertThat(activeToken.isRevoked()).isTrue();
        verify(refreshTokenRepository).save(activeToken);
    }
}
