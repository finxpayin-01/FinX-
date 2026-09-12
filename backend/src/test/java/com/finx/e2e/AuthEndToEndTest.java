package com.finx.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finx.audit.repository.AuditLogRepository;
import com.finx.auth.dto.request.LoginRequest;
import com.finx.auth.dto.request.LogoutRequest;
import com.finx.auth.dto.request.RegisterRequest;
import com.finx.auth.dto.request.TokenRefreshRequest;
import com.finx.common.enums.Role;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthEndToEndTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    @DisplayName("Complete E2E Auth Flow: Register -> Login -> Me -> Refresh -> Logout -> Verify Invalidation")
    void testCompleteAuthLifecycle() throws Exception {
        String email = "buyer.e2e@finx.com";
        String password = "StrongPassword123!";

        // 1. Register new BUYER
        RegisterRequest registerRequest = new RegisterRequest("E2E Buyer", email, password, Role.BUYER);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.data.user.email").value(email))
                .andExpect(jsonPath("$.data.user.role").value("BUYER"))
                .andExpect(jsonPath("$.data.user.uid").isNotEmpty())
                .andExpect(jsonPath("$.data.user.uid").value(org.hamcrest.Matchers.matchesPattern("^USR-[0-9A-F]{8}$")));

        // 2. Attempt duplicate email registration -> should fail with 409 Conflict
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));

        // 3. Attempt ADMIN registration -> should fail with 400 Bad Request
        RegisterRequest adminRequest = new RegisterRequest("Admin Attempt", "admin.fake@finx.com", password, Role.ADMIN);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adminRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        // 4. Login with valid credentials
        LoginRequest loginRequest = new LoginRequest(email, password);
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andReturn();

        JsonNode loginJson = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String initialAccessToken = loginJson.get("data").get("accessToken").asText();
        String initialRefreshToken = loginJson.get("data").get("refreshToken").asText();

        // 5. Login with invalid password -> 401 Unauthorized
        LoginRequest badLoginRequest = new LoginRequest(email, "WrongPassword123!");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badLoginRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));

        // 6. Access protected /api/auth/me with Bearer token -> 200 OK
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + initialAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.uid").value(org.hamcrest.Matchers.matchesPattern("^USR-[0-9A-F]{8}$")))
                .andExpect(jsonPath("$.data.role").value("BUYER"));

        // 7. Access protected /api/auth/me without token -> 401 Unauthorized
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));

        // 8. Refresh Token -> rotate token pair
        TokenRefreshRequest refreshRequest = new TokenRefreshRequest(initialRefreshToken);
        MvcResult refreshResult = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andReturn();

        JsonNode refreshJson = objectMapper.readTree(refreshResult.getResponse().getContentAsString());
        String newAccessToken = refreshJson.get("data").get("accessToken").asText();
        String newRefreshToken = refreshJson.get("data").get("refreshToken").asText();

        assertThat(newRefreshToken).isNotEqualTo(initialRefreshToken);

        // 9. Access protected /api/auth/me with NEW access token -> 200 OK
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + newAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(jsonPath("$.data.uid").value(org.hamcrest.Matchers.matchesPattern("^USR-[0-9A-F]{8}$")));

        // 10. Attempt to reuse OLD refresh token -> 401 Unauthorized (revoked)
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TokenRefreshRequest(initialRefreshToken))))
                .andExpect(status().isUnauthorized());

        // 11. Logout using current refresh token
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LogoutRequest(newRefreshToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 12. Attempt to refresh with logged-out token -> 401 Unauthorized
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TokenRefreshRequest(newRefreshToken))))
                .andExpect(status().isUnauthorized());

        // 13. Verify Database Persistence & Audit Logging
        Optional<User> persistedUser = userRepository.findByEmail(email);
        assertThat(persistedUser).isPresent();
        assertThat(persistedUser.get().getName()).isEqualTo("E2E Buyer");

        long auditCount = auditLogRepository.count();
        assertThat(auditCount).isGreaterThanOrEqualTo(3); // Registered, Login, Refreshed, etc.
    }

    @Test
    @DisplayName("Verify registration works for all supported roles and aliases (BUYER, SELLER, CORPORATE, VENDOR, PROJECT_MANAGER, FINANCE)")
    void testRegistrationWithAllSupportedRolesAndAliases() throws Exception {
        String password = "StrongPassword123!";

        // 1. BUYER
        String buyerEmail = "test.buyer@finx.com";
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Buyer User\",\"email\":\"" + buyerEmail + "\",\"password\":\"" + password + "\",\"role\":\"BUYER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.user.role").value("BUYER"));

        // 2. SELLER
        String sellerEmail = "test.seller@finx.com";
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Seller User\",\"email\":\"" + sellerEmail + "\",\"password\":\"" + password + "\",\"role\":\"SELLER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.user.role").value("SELLER"));

        // 3. CORPORATE alias (maps to BUYER)
        String corporateEmail = "test.corporate@finx.com";
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Corporate User\",\"email\":\"" + corporateEmail + "\",\"password\":\"" + password + "\",\"role\":\"CORPORATE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.user.role").value("BUYER"));

        // 4. VENDOR alias (maps to SELLER)
        String vendorEmail = "test.vendor@finx.com";
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Vendor User\",\"email\":\"" + vendorEmail + "\",\"password\":\"" + password + "\",\"role\":\"VENDOR\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.user.role").value("SELLER"));

        // 5. PROJECT_MANAGER
        String pmEmail = "test.pm@finx.com";
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"PM User\",\"email\":\"" + pmEmail + "\",\"password\":\"" + password + "\",\"role\":\"PROJECT_MANAGER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.user.role").value("PROJECT_MANAGER"));

        // 6. FINANCE
        String financeEmail = "test.finance@finx.com";
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Finance User\",\"email\":\"" + financeEmail + "\",\"password\":\"" + password + "\",\"role\":\"FINANCE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.user.role").value("FINANCE"));

        // 7. Invalid role -> 400 Bad Request
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Invalid User\",\"email\":\"invalid.role@finx.com\",\"password\":\"" + password + "\",\"role\":\"SUPERUSER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("Health endpoint returns UP status")
    void testHealthEndpoint() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.service").value("finx-backend"));
    }
}
