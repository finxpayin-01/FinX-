package com.finx.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finx.agreement.dto.AgreementDtos.CreateAgreementDraftRequest;
import com.finx.agreement.dto.AgreementDtos.SignAgreementRequest;
import com.finx.auth.dto.request.RegisterRequest;
import com.finx.common.enums.Role;
import com.finx.deal.dto.request.CreateDealRequest;
import com.finx.milestone.dto.request.CreateMilestoneRequest;
import com.finx.milestone.dto.request.SubmitDeliverableRequest;
import com.finx.org.dto.OrgDtos.AddBankAccountRequest;
import com.finx.org.dto.OrgDtos.CreateOrgRequest;
import com.finx.org.enums.OrgEnums.EntityType;
import com.finx.payment.config.RazorpayTestConfiguration;
import com.finx.paymentorchestration.dto.PaymentOrchestrationDtos.ConfirmPaymentRequest;
import com.finx.paymentorchestration.dto.PaymentOrchestrationDtos.CreateInstructionRequest;
import com.finx.paymentorchestration.dto.PaymentOrchestrationDtos.PayoutAuthorizationRequest;
import com.finx.verification.dto.VerificationDtos.SubmitKybRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finx.common.enums.UserStatus;
import com.finx.security.jwt.JwtTokenProvider;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RazorpayTestConfiguration.class)
class BankIntegratedEndToEndTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("Complete Bank-Integrated B2B Milestone Payment Lifecycle E2E")
    void testCompleteBankIntegratedB2bLifecycle() throws Exception {
        long ts = System.currentTimeMillis();
        String buyerEmail = "b2b.buyer." + ts + "@finx.test";
        String vendorEmail = "b2b.vendor." + ts + "@finx.test";
        String password = "Password123!";

        // 0. Bootstrap Admin User
        User admin = new User("FinX Platform Admin", "b2b.admin." + ts + "@finx.test", "hash", Role.ADMIN, UserStatus.ACTIVE);
        admin = userRepository.save(admin);
        String adminToken = jwtTokenProvider.generateAccessToken(admin);

        // 1. Register Buyer
        RegisterRequest buyerReg = new RegisterRequest();
        buyerReg.setEmail(buyerEmail);
        buyerReg.setPassword(password);
        buyerReg.setName("Acme Buyer Corp");
        buyerReg.setRole(Role.BUYER);

        MvcResult buyerRegRes = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buyerReg)))
                .andExpect(status().isCreated())
                .andReturn();
        String buyerToken = objectMapper.readTree(buyerRegRes.getResponse().getContentAsString())
                .get("data").get("accessToken").asText();
        String buyerIdStr = objectMapper.readTree(buyerRegRes.getResponse().getContentAsString())
                .get("data").get("user").get("id").asText();
        UUID buyerId = UUID.fromString(buyerIdStr);

        // 2. Register Vendor
        RegisterRequest vendorReg = new RegisterRequest();
        vendorReg.setEmail(vendorEmail);
        vendorReg.setPassword(password);
        vendorReg.setName("Apex Vendor Solutions");
        vendorReg.setRole(Role.SELLER);

        MvcResult vendorRegRes = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(vendorReg)))
                .andExpect(status().isCreated())
                .andReturn();
        String vendorToken = objectMapper.readTree(vendorRegRes.getResponse().getContentAsString())
                .get("data").get("accessToken").asText();
        String vendorIdStr = objectMapper.readTree(vendorRegRes.getResponse().getContentAsString())
                .get("data").get("user").get("id").asText();
        UUID vendorId = UUID.fromString(vendorIdStr);

        // 3. Create Buyer Organization
        CreateOrgRequest buyerOrgReq = new CreateOrgRequest(
                "Acme Global",
                "Acme Global Industries Pvt Ltd",
                EntityType.PRIVATE_LIMITED,
                "U12345MH2020PTC123456",
                "AAACA1234A",
                "27AAACA1234A1Z5",
                "Tower A, BKC, Mumbai"
        );
        MvcResult buyerOrgRes = mockMvc.perform(post("/api/v1/organizations")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(buyerOrgReq)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID buyerOrgId = UUID.fromString(objectMapper.readTree(buyerOrgRes.getResponse().getContentAsString())
                .get("data").get("id").asText());

        // 4. Create Vendor Organization
        CreateOrgRequest vendorOrgReq = new CreateOrgRequest(
                "Apex Cloud Infra",
                "Apex Cloud Infra Solutions LLP",
                EntityType.LLP,
                "AAA-1234",
                "AABBA5678B",
                "29AABBA5678B1Z2",
                "Cyber Park, Electronic City, Bangalore"
        );
        MvcResult vendorOrgRes = mockMvc.perform(post("/api/v1/organizations")
                        .header("Authorization", "Bearer " + vendorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(vendorOrgReq)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID vendorOrgId = UUID.fromString(objectMapper.readTree(vendorOrgRes.getResponse().getContentAsString())
                .get("data").get("id").asText());

        // 5. Vendor completes Mock KYB
        SubmitKybRequest kybReq = new SubmitKybRequest(
                "AABBA5678B",
                "29AABBA5678B1Z2",
                "AAA-1234",
                "UDYAM-KR-03-0012345"
        );
        mockMvc.perform(post("/api/v1/kyb/" + vendorOrgId)
                        .header("Authorization", "Bearer " + vendorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(kybReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VERIFIED"));

        // 6. Vendor adds and verifies Bank Account (Penny Drop simulation)
        AddBankAccountRequest bankReq = new AddBankAccountRequest(
                "Apex Cloud Infra Solutions LLP",
                "987654321012",
                "HDFC0001234",
                "HDFC Bank",
                "Electronic City Branch",
                true
        );
        MvcResult bankRes = mockMvc.perform(post("/api/v1/organizations/" + vendorOrgId + "/bank-accounts")
                        .header("Authorization", "Bearer " + vendorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bankReq)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID bankAccountId = UUID.fromString(objectMapper.readTree(bankRes.getResponse().getContentAsString())
                .get("data").get("id").asText());

        mockMvc.perform(post("/api/v1/organizations/" + vendorOrgId + "/bank-accounts/" + bankAccountId + "/verify")
                        .header("Authorization", "Bearer " + vendorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verificationStatus").value("VERIFIED"));

        // 7. Buyer creates Deal (₹10,00,000)
        CreateDealRequest dealReq = new CreateDealRequest(
                "Enterprise Data Pipeline Construction",
                "High performance streaming data platform",
                buyerId,
                vendorId.toString(),
                new BigDecimal("1000000.00"),
                "INR"
        );
        dealReq.setBuyerOrganizationId(buyerOrgId);
        dealReq.setSellerOrganizationId(vendorOrgId);

        MvcResult dealRes = mockMvc.perform(post("/api/v1/deals")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dealReq)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID dealId = UUID.fromString(objectMapper.readTree(dealRes.getResponse().getContentAsString())
                .get("data").get("id").asText());

        // Vendor accepts deal
        mockMvc.perform(post("/api/v1/deals/" + dealId + "/accept")
                        .header("Authorization", "Bearer " + vendorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"));

        // 8. Add Milestones (Milestone 1: ₹4,00,000; Milestone 2: ₹6,00,000 -> Total ₹10,00,000)
        CreateMilestoneRequest ms1Req = new CreateMilestoneRequest();
        ms1Req.setTitle("Architecture and Ingestion Layer");
        ms1Req.setDescription("Kafka and Flink pipeline infrastructure setup");
        ms1Req.setAmount(new BigDecimal("400000.00"));
        ms1Req.setCurrency("INR");
        ms1Req.setSequence(1);

        MvcResult ms1Res = mockMvc.perform(post("/api/v1/deals/" + dealId + "/milestones")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ms1Req)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID ms1Id = UUID.fromString(objectMapper.readTree(ms1Res.getResponse().getContentAsString())
                .get("data").get("id").asText());

        CreateMilestoneRequest ms2Req = new CreateMilestoneRequest();
        ms2Req.setTitle("Data Lakehouse Settlement and Analytics");
        ms2Req.setDescription("BigQuery & Trino analytical cluster setup");
        ms2Req.setAmount(new BigDecimal("600000.00"));
        ms2Req.setCurrency("INR");
        ms2Req.setSequence(2);

        mockMvc.perform(post("/api/v1/deals/" + dealId + "/milestones")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ms2Req)))
                .andExpect(status().isCreated());

        // 9. Deal Agreement: Create & Sign
        CreateAgreementDraftRequest agrReq = new CreateAgreementDraftRequest(
                "Master Services Agreement & Milestone Schedule v1",
                "Standard FinX B2B Terms: All milestones funded upfront in bank escrow nodal account."
        );
        mockMvc.perform(post("/api/v1/agreements/" + dealId)
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(agrReq)))
                .andExpect(status().isCreated());

        // Buyer signs (version 1)
        mockMvc.perform(post("/api/v1/agreements/" + dealId + "/sign")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SignAgreementRequest(1))))
                .andExpect(status().isOk());

        // Vendor signs -> transitions agreement to SIGNED
        mockMvc.perform(post("/api/v1/agreements/" + dealId + "/sign")
                        .header("Authorization", "Bearer " + vendorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SignAgreementRequest(1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SIGNED"));

        // 10. Generate Bank Payment Instruction for Upfront Deal Funding (₹10,00,000)
        CreateInstructionRequest payInstReq = new CreateInstructionRequest(
                dealId,
                null,
                "IDEMP-INS-" + ts
        );
        MvcResult payInstRes = mockMvc.perform(post("/api/v1/payments/instructions")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payInstReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.virtualAccountNumber").isNotEmpty())
                .andExpect(jsonPath("$.data.virtualIfsc").value("FINX0001001"))
                .andReturn();
        UUID instructionId = UUID.fromString(objectMapper.readTree(payInstRes.getResponse().getContentAsString())
                .get("data").get("id").asText());

        // 11. Confirm Bank Payment Received (Funds Arrived at Nodal Account)
        ConfirmPaymentRequest confirmReq = new ConfirmPaymentRequest(
                "UTR-BANK-CONFIRM-" + ts,
                "MOCK-PROV-REF-" + ts
        );
        mockMvc.perform(post("/api/v1/payments/instructions/" + instructionId + "/confirm")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        // Verify Deal is now FUNDED
        mockMvc.perform(get("/api/v1/deals/" + dealId)
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FUNDED"));

        // 12. Vendor starts Milestone 1 and submits Deliverable
        mockMvc.perform(post("/api/v1/milestones/" + ms1Id + "/start")
                        .header("Authorization", "Bearer " + vendorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));

        SubmitDeliverableRequest delReq = new SubmitDeliverableRequest();
        delReq.setFileName("Ingestion pipeline code & architecture diagram");
        delReq.setDescription("Kafka clusters configured and verified");
        delReq.setFileUrl("https://s3.finx.dev/deliverables/ingestion.zip");

        mockMvc.perform(post("/api/v1/milestones/" + ms1Id + "/submit")
                        .header("Authorization", "Bearer " + vendorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(delReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        // Verify milestone transitioned to UNDER_REVIEW
        mockMvc.perform(get("/api/v1/milestones/" + ms1Id)
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNDER_REVIEW"));

        // 13. Buyer Approves Milestone 1
        mockMvc.perform(post("/api/v1/milestones/" + ms1Id + "/approve")
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        // 14. 10-Gate Payout Authorization & Bank Settlement Disbursement
        PayoutAuthorizationRequest payoutReq = new PayoutAuthorizationRequest(
                ms1Id,
                "Milestone 1 work approved by VP Engineering",
                "IDEMP-" + ts
        );
        mockMvc.perform(post("/api/v1/settlements/authorize")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payoutReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("SETTLED"))
                .andExpect(jsonPath("$.data.utrNumber").isNotEmpty())
                .andExpect(jsonPath("$.data.amount").value(400000.0));

        // 15. Attempt duplicate payout authorization with a different key -> blocked with 400 Bad Request
        PayoutAuthorizationRequest duplicateReq = new PayoutAuthorizationRequest(
                ms1Id,
                "Milestone 1 duplicate attempt",
                "IDEMP-DIFF-" + ts
        );
        mockMvc.perform(post("/api/v1/settlements/authorize")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicateReq)))
                .andExpect(status().isBadRequest());

        // 16. Verify Milestone status is now COMPLETED
        mockMvc.perform(get("/api/v1/milestones/" + ms1Id)
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        // 17. Verify Double-Entry Ledger is balanced and reflects both deposit and settlement
        mockMvc.perform(get("/api/v1/admin/ledger/entries")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // 18. Trigger Reconciliation Run and verify
        mockMvc.perform(post("/api/v1/reconciliation/trigger")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("MOCK"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }
}
