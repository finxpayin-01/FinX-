package com.finx.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finx.deal.dto.request.CreateDealRequest;
import com.finx.milestone.dto.request.CreateMilestoneRequest;
import com.finx.milestone.dto.request.SubmitDeliverableRequest;
import com.finx.payment.dto.request.CreateOrderRequest;
import com.finx.payment.dto.request.VerifyPaymentRequest;
import com.finx.payment.config.RazorpayTestConfiguration;
import com.finx.payment.service.RazorpayService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RazorpayTestConfiguration.class)
class SecurityHardenedTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RazorpayService razorpayService;

    @SuppressWarnings("unused")
    private static class RegisteredUser {
        final UUID id;
        final String email;
        final String token;

        RegisteredUser(UUID id, String email, String token) {
            this.id = id;
            this.email = email;
            this.token = token;
        }
    }

    private RegisteredUser registerAndLogin(String name, String email, String role) throws Exception {
        String regJson = String.format("{\"name\":\"%s\",\"email\":\"%s\",\"password\":\"Password123!\",\"role\":\"%s\"}",
                name, email, role);

        MvcResult regRes = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(regJson))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(regRes.getResponse().getContentAsString());
        UUID id = UUID.fromString(json.get("data").get("user").get("id").asText());
        String token = json.get("data").get("accessToken").asText();

        return new RegisteredUser(id, email, token);
    }

    @Test
    @DisplayName("Comprehensive IDOR & Role Authorization Security Test Suite")
    void testSecurityAndIdorProtections() throws Exception {
        long ts = System.currentTimeMillis();

        // 1. Provision 4 distinct principals: Buyer A, Buyer B, Seller A, Seller B
        RegisteredUser buyerA = registerAndLogin("Buyer Alpha", "buyerA." + ts + "@finx.sec", "BUYER");
        RegisteredUser buyerB = registerAndLogin("Buyer Beta", "buyerB." + ts + "@finx.sec", "BUYER");
        RegisteredUser sellerA = registerAndLogin("Seller Alpha", "sellerA." + ts + "@finx.sec", "SELLER");
        RegisteredUser sellerB = registerAndLogin("Seller Beta", "sellerB." + ts + "@finx.sec", "SELLER");

        // 2. Buyer A creates Deal A assigned to Seller A
        CreateDealRequest dealReqA = new CreateDealRequest(
                "Secure Project Alpha",
                "Mission-critical infrastructure migration",
                buyerA.id,
                sellerA.id,
                new BigDecimal("20000.00"),
                "INR"
        );

        MvcResult dealResA = mockMvc.perform(post("/api/deals")
                        .header("Authorization", "Bearer " + buyerA.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dealReqA)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID dealAId = UUID.fromString(objectMapper.readTree(dealResA.getResponse().getContentAsString()).get("data").get("id").asText());

        // 3. IDOR TEST: Buyer B attempts to read Deal A -> 401 Unauthorized
        mockMvc.perform(get("/api/deals/" + dealAId)
                        .header("Authorization", "Bearer " + buyerB.token))
                .andExpect(status().isUnauthorized());

        // 4. IDOR TEST: Seller B attempts to read Deal A -> 401 Unauthorized
        mockMvc.perform(get("/api/deals/" + dealAId)
                        .header("Authorization", "Bearer " + sellerB.token))
                .andExpect(status().isUnauthorized());

        // 5. AUTHORIZATION TEST: Seller B attempts to accept Deal A -> 401 Unauthorized
        mockMvc.perform(patch("/api/deals/" + dealAId + "/accept")
                        .header("Authorization", "Bearer " + sellerB.token))
                .andExpect(status().isUnauthorized());

        // 6. Authorized Seller A accepts Deal A -> 200 OK ACTIVE
        mockMvc.perform(patch("/api/deals/" + dealAId + "/accept")
                        .header("Authorization", "Bearer " + sellerA.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"));

        // 7. Buyer A creates Milestone 1 on Deal A
        CreateMilestoneRequest msReq = new CreateMilestoneRequest();
        msReq.setTitle("Milestone 1: Zero Trust Network Setup");
        msReq.setDescription("mTLS and Service Mesh authorization policies");
        msReq.setSequence(1);
        msReq.setAmount(new BigDecimal("10000.00"));
        msReq.setCurrency("INR");
        msReq.setDueDate(Instant.now().plusSeconds(86400 * 5));

        MvcResult msRes = mockMvc.perform(post("/api/deals/" + dealAId + "/milestones")
                        .header("Authorization", "Bearer " + buyerA.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(msReq)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID msId = UUID.fromString(objectMapper.readTree(msRes.getResponse().getContentAsString()).get("data").get("id").asText());

        // 8. IDOR TEST: Buyer B attempts to read Milestone 1 -> 401 Unauthorized
        mockMvc.perform(get("/api/milestones/" + msId)
                        .header("Authorization", "Bearer " + buyerB.token))
                .andExpect(status().isUnauthorized());

        // 9. AUTHORIZATION TEST: Seller B attempts to start Milestone 1 -> 401 Unauthorized
        mockMvc.perform(post("/api/milestones/" + msId + "/start")
                        .header("Authorization", "Bearer " + sellerB.token))
                .andExpect(status().isUnauthorized());

        // 10. Authorized Seller A starts Milestone 1 -> 200 OK IN_PROGRESS
        mockMvc.perform(post("/api/milestones/" + msId + "/start")
                        .header("Authorization", "Bearer " + sellerA.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));

        // 11. AUTHORIZATION TEST: Buyer A attempts to submit deliverable (impersonating vendor) -> 401 Unauthorized
        SubmitDeliverableRequest delivReq = new SubmitDeliverableRequest();
        delivReq.setFileName("security_mesh_config.zip");
        delivReq.setFileUrl("https://storage.finx.local/deliverables/security_mesh_config.zip");
        delivReq.setDescription("Linkerd configuration and certificates");

        mockMvc.perform(post("/api/milestones/" + msId + "/submit")
                        .header("Authorization", "Bearer " + buyerA.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(delivReq)))
                .andExpect(status().isUnauthorized());

        // 12. Authorized Seller A submits deliverable -> 201 Created
        mockMvc.perform(post("/api/milestones/" + msId + "/submit")
                        .header("Authorization", "Bearer " + sellerA.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(delivReq)))
                .andExpect(status().isCreated());

        // 13. AUTHORIZATION TEST: Seller A attempts to approve own milestone -> 401 Unauthorized
        mockMvc.perform(post("/api/milestones/" + msId + "/approve")
                        .header("Authorization", "Bearer " + sellerA.token))
                .andExpect(status().isUnauthorized());

        // 14. AUTHORIZATION TEST: Buyer B attempts to approve Buyer A's milestone -> 401 Unauthorized
        mockMvc.perform(post("/api/milestones/" + msId + "/approve")
                        .header("Authorization", "Bearer " + buyerB.token))
                .andExpect(status().isUnauthorized());

        // 15. Authorized Buyer A approves Milestone 1 -> 200 OK APPROVED
        mockMvc.perform(post("/api/milestones/" + msId + "/approve")
                        .header("Authorization", "Bearer " + buyerA.token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        // 16. Buyer A creates Razorpay order
        CreateOrderRequest orderReq = new CreateOrderRequest(dealAId, msId);
        orderReq.setIdempotencyKey("sec_order_" + ts);

        MvcResult orderRes = mockMvc.perform(post("/api/payments/create-order")
                        .header("Authorization", "Bearer " + buyerA.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderReq)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode orderJson = objectMapper.readTree(orderRes.getResponse().getContentAsString());
        UUID paymentId = UUID.fromString(orderJson.get("data").get("paymentId").asText());
        String orderId = orderJson.get("data").get("orderId").asText();

        // 17. IDOR TEST: Buyer B attempts to verify Buyer A's payment -> 401 Unauthorized
        String razorpayPaymentId = "pay_sec_" + ts;
        VerifyPaymentRequest verifyReq = new VerifyPaymentRequest(
                paymentId,
                orderId,
                razorpayPaymentId,
                razorpayService.generateTestSignature(orderId, razorpayPaymentId)
        );

        mockMvc.perform(post("/api/payments/verify")
                        .header("Authorization", "Bearer " + buyerB.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verifyReq)))
                .andExpect(status().isUnauthorized());

        // 18. IDOR TEST: Buyer B attempts to read Deal A's escrow balance -> 401 Unauthorized
        mockMvc.perform(get("/api/escrow/deal/" + dealAId)
                        .header("Authorization", "Bearer " + buyerB.token))
                .andExpect(status().isUnauthorized());

        // 19. IDOR TEST: Buyer B attempts to read Deal A's escrow ledger -> 401 Unauthorized
        mockMvc.perform(get("/api/escrow/ledger/deal/" + dealAId)
                        .header("Authorization", "Bearer " + buyerB.token))
                .andExpect(status().isUnauthorized());

        // 20. Authorized Buyer A verifies payment -> 200 OK SUCCESS
        mockMvc.perform(post("/api/payments/verify")
                        .header("Authorization", "Bearer " + buyerA.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verifyReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        // 21. AUTHORIZATION TEST: Seller A attempts to release escrow -> 401 Unauthorized
        mockMvc.perform(post("/api/escrow/" + msId + "/release")
                        .header("Authorization", "Bearer " + sellerA.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"Unauthorized release attempt\"}"))
                .andExpect(status().isUnauthorized());

        // 22. AUTHORIZATION TEST: Buyer B attempts to release Deal A's escrow -> 401 Unauthorized
        mockMvc.perform(post("/api/escrow/" + msId + "/release")
                        .header("Authorization", "Bearer " + buyerB.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"Buyer B unauthorized release\"}"))
                .andExpect(status().isUnauthorized());

        // 23. Authorized Buyer A releases escrow -> 200 OK RELEASE
        mockMvc.perform(post("/api/escrow/" + msId + "/release")
                        .header("Authorization", "Bearer " + buyerA.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"Authorized release to vendor\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transactionType").value("RELEASE"))
                .andExpect(jsonPath("$.data.balanceAfter").value(0.00));

        // 24. DOUBLE-RELEASE PREVENTION: Second release attempt must be rejected with 400 Bad Request
        mockMvc.perform(post("/api/escrow/" + msId + "/release")
                        .header("Authorization", "Bearer " + buyerA.token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"Duplicate release attempt\"}"))
                .andExpect(status().isBadRequest());

        // 25. AUTHENTICATION TEST: Expired / forged JWT signature rejected -> 401 Unauthorized
        mockMvc.perform(get("/api/deals/" + dealAId)
                        .header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.forged.signature"))
                .andExpect(status().isUnauthorized());

        // 26. AUTHENTICATION TEST: Missing Authorization header -> 401 Unauthorized
        mockMvc.perform(get("/api/deals/" + dealAId))
                .andExpect(status().isUnauthorized());
    }
}
