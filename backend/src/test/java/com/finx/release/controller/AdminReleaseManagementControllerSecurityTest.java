package com.finx.release.controller;

import com.finx.payment.config.RazorpayTestConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RazorpayTestConfiguration.class)
class AdminReleaseManagementControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN role is authorized to access operational release summary")
    void adminCanAccessSummary() throws Exception {
        mockMvc.perform(get("/api/v1/finance/release-operations/summary"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "FINANCE")
    @DisplayName("FINANCE role is authorized to access operational release summary")
    void financeCanAccessSummary() throws Exception {
        mockMvc.perform(get("/api/v1/finance/release-operations/summary"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "BUYER")
    @DisplayName("BUYER role is forbidden from operational release summary")
    void buyerForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/finance/release-operations/summary"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SELLER")
    @DisplayName("SELLER role is forbidden from operational release summary")
    void sellerForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/finance/release-operations/summary"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "PROJECT_MANAGER")
    @DisplayName("PROJECT_MANAGER role is forbidden from operational release summary")
    void projectManagerForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/finance/release-operations/summary"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Unauthenticated request is denied access to operational release summary")
    void unauthenticatedDenied() throws Exception {
        mockMvc.perform(get("/api/v1/finance/release-operations/summary"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertThat(status).isIn(401, 403);
                });
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN role is authorized to access platform certification status")
    void adminCanAccessCertification() throws Exception {
        mockMvc.perform(get("/api/v1/finance/release-operations/certification"))
                .andExpect(status().isOk());
    }
}
