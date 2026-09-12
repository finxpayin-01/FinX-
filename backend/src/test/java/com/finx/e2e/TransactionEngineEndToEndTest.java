package com.finx.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finx.auth.dto.request.RegisterRequest;
import com.finx.common.enums.Role;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RazorpayTestConfiguration.class)
class TransactionEngineEndToEndTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RazorpayService razorpayService;

    @Autowired
    private com.finx.user.repository.UserRepository userRepository;

    @Autowired
    private com.finx.security.jwt.JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("Complete Thursday E2E Transaction Engine: Deal -> Milestone -> Deliverable -> Approve -> Pay -> Escrow Fund -> Release -> Double-Release Protection")
    void testCompleteTransactionLifecycle() throws Exception {
        long timestamp = System.currentTimeMillis();
        String buyerEmail = "corp.buyer." + timestamp + "@finx.test";
        String sellerEmail = "vendor.seller." + timestamp + "@finx.test";
        String password = "Password123!";

        // 1. Register Buyer
        RegisterRequest buyerReg = new RegisterRequest("Global Enterprise Buyer", buyerEmail, password, Role.BUYER);
        MvcResult buyerRegResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buyerReg)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode buyerJson = objectMapper.readTree(buyerRegResult.getResponse().getContentAsString());
        String buyerToken = buyerJson.get("data").get("accessToken").asText();
        UUID buyerId = UUID.fromString(buyerJson.get("data").get("user").get("id").asText());

        // 2. Register Seller
        RegisterRequest sellerReg = new RegisterRequest("Apex Cloud Solutions", sellerEmail, password, Role.SELLER);
        MvcResult sellerRegResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sellerReg)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode sellerJson = objectMapper.readTree(sellerRegResult.getResponse().getContentAsString());
        String sellerToken = sellerJson.get("data").get("accessToken").asText();
        UUID sellerId = UUID.fromString(sellerJson.get("data").get("user").get("id").asText());

        // 3. Buyer Creates Deal
        CreateDealRequest dealRequest = new CreateDealRequest(
                "Kubernetes Multi-Cloud Deployment",
                "Complete migration of legacy microservices to orchestrated EKS/GKE clusters.",
                buyerId,
                sellerId,
                new BigDecimal("25000.00"),
                "INR"
        );
        MvcResult dealResult = mockMvc.perform(post("/api/deals")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dealRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn();
        JsonNode dealJson = objectMapper.readTree(dealResult.getResponse().getContentAsString());
        UUID dealId = UUID.fromString(dealJson.get("data").get("id").asText());

        // 4. Seller Accepts Deal -> ACTIVE
        mockMvc.perform(patch("/api/deals/" + dealId + "/accept")
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        // 5. Buyer Creates Milestone for Deal
        CreateMilestoneRequest milestoneRequest = new CreateMilestoneRequest();
        milestoneRequest.setTitle("Sprint 1: Infrastructure as Code");
        milestoneRequest.setDescription("Terraform scripts and container registry provisioning");
        milestoneRequest.setSequence(1);
        milestoneRequest.setAmount(new BigDecimal("10000.00"));
        milestoneRequest.setCurrency("INR");
        milestoneRequest.setDueDate(Instant.now().plusSeconds(86400 * 7));

        MvcResult milestoneResult = mockMvc.perform(post("/api/deals/" + dealId + "/milestones")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(milestoneRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn();
        JsonNode milestoneJson = objectMapper.readTree(milestoneResult.getResponse().getContentAsString());
        UUID milestoneId = UUID.fromString(milestoneJson.get("data").get("id").asText());

        // 6. Seller Starts Milestone -> IN_PROGRESS
        mockMvc.perform(post("/api/milestones/" + milestoneId + "/start")
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));

        // 7. Seller Submits Deliverable -> UNDER_REVIEW
        SubmitDeliverableRequest deliverableRequest = new SubmitDeliverableRequest();
        deliverableRequest.setFileName("terraform_cloud_manifests.zip");
        deliverableRequest.setFileUrl("https://storage.finx.local/deliverables/terraform_cloud_manifests.zip");
        deliverableRequest.setDescription("Production ready Terraform manifests and security scans");

        mockMvc.perform(post("/api/milestones/" + milestoneId + "/submit")
                        .header("Authorization", "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deliverableRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        // Verify milestone is now UNDER_REVIEW
        mockMvc.perform(get("/api/milestones/" + milestoneId)
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNDER_REVIEW"));

        // Security Check: Seller attempting to approve own milestone must fail
        mockMvc.perform(post("/api/milestones/" + milestoneId + "/approve")
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isUnauthorized());

        // 8. Buyer Approves Milestone -> APPROVED
        mockMvc.perform(post("/api/milestones/" + milestoneId + "/approve")
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        // 9. Buyer Initiates Razorpay Payment Order
        CreateOrderRequest orderReq = new CreateOrderRequest(dealId, milestoneId);
        orderReq.setIdempotencyKey("idemp_" + timestamp);

        MvcResult orderResult = mockMvc.perform(post("/api/payments/create-order")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.orderId").isNotEmpty())
                .andExpect(jsonPath("$.data.paymentId").isNotEmpty())
                .andReturn();
        JsonNode orderJson = objectMapper.readTree(orderResult.getResponse().getContentAsString());
        String orderId = orderJson.get("data").get("orderId").asText();
        UUID paymentId = UUID.fromString(orderJson.get("data").get("paymentId").asText());

        // 10. Buyer Verifies Payment -> Escrow Account & Ledger Funded
        String razorpayPaymentId = "pay_test_txn_" + timestamp;
        VerifyPaymentRequest verifyReq = new VerifyPaymentRequest(
                paymentId,
                orderId,
                razorpayPaymentId,
                razorpayService.generateTestSignature(orderId, razorpayPaymentId)
        );
        mockMvc.perform(post("/api/payments/verify")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(verifyReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        // 11. Verify Escrow Account Balance & Ledger
        mockMvc.perform(get("/api/escrow/deal/" + dealId)
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(10000.00));

        mockMvc.perform(get("/api/escrow/ledger/deal/" + dealId)
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].transactionType").value("FUND"))
                .andExpect(jsonPath("$.data[0].amount").value(10000.00));

        // 12. Buyer Releases Escrow Funds to Vendor
        mockMvc.perform(post("/api/escrow/" + milestoneId + "/release")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"Corporate approved milestone completion\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transactionType").value("RELEASE"))
                .andExpect(jsonPath("$.data.amount").value(10000.00))
                .andExpect(jsonPath("$.data.balanceAfter").value(0.00));

        // 13. Verify Escrow Balance is now 0.00
        mockMvc.perform(get("/api/escrow/deal/" + dealId)
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(0.00));

        // 14. Double-Release Prevention: Second release attempt must be rejected with 400 Bad Request
        mockMvc.perform(post("/api/escrow/" + milestoneId + "/release")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"Attempt duplicate release\"}"))
                .andExpect(status().isBadRequest());

        // 15. Verify Admin Authorization & Audit Trail
        // Buyer must be forbidden (403) from admin endpoints
        mockMvc.perform(get("/api/admin/dashboard")
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isForbidden());

        // Admin user has access
        String adminEmail = "platform.admin." + timestamp + "@finx.test";
        com.finx.user.entity.User admin = new com.finx.user.entity.User("Platform Admin", adminEmail, "hash", Role.ADMIN, com.finx.common.enums.UserStatus.ACTIVE);
        admin = userRepository.save(admin);
        String adminToken = jwtTokenProvider.generateAccessToken(admin);

        mockMvc.perform(get("/api/admin/dashboard")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalDeals").isNotEmpty())
                .andExpect(jsonPath("$.data.totalFundsDeposited").isNotEmpty());

        mockMvc.perform(get("/api/admin/audit-logs")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }
}
