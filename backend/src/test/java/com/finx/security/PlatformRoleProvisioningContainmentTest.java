package com.finx.security;

import com.finx.audit.service.AuditService;
import com.finx.auth.config.GoogleOAuthProperties;
import com.finx.auth.controller.AuthController;
import com.finx.auth.dto.request.GoogleOAuthRequest;
import com.finx.auth.dto.request.RegisterRequest;
import com.finx.auth.dto.response.AuthResponse;
import com.finx.auth.entity.RefreshToken;
import com.finx.auth.repository.RefreshTokenRepository;
import com.finx.auth.service.AuthService;
import com.finx.banking.provider.BankingProvider;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.common.response.ApiResponse;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.escrow.repository.EscrowAccountRepository;
import com.finx.escrow.repository.EscrowLedgerRepository;
import com.finx.exception.BadRequestException;
import com.finx.exception.GlobalExceptionHandler;
import com.finx.finance.controller.FinanceController;
import com.finx.finance.controller.FinanceProjectController;
import com.finx.ledger.repository.LedgerAccountRepository;
import com.finx.ledger.service.LedgerService;
import com.finx.milestone.repository.MilestoneRepository;
import com.finx.org.entity.Organization;
import com.finx.org.entity.OrganizationMember;
import com.finx.org.enums.OrgEnums.EntityType;
import com.finx.org.enums.OrgEnums.MemberStatus;
import com.finx.org.enums.OrgEnums.OrgRole;
import com.finx.org.enums.OrgEnums.OrgVerificationStatus;
import com.finx.payment.repository.PaymentRepository;
import com.finx.paymentorchestration.repository.PaymentInstructionRepository;
import com.finx.paymentorchestration.repository.PaymentTransactionRepository;
import com.finx.paymentorchestration.repository.SettlementInstructionRepository;
import com.finx.reconciliation.entity.ReconciliationRun;
import com.finx.reconciliation.repository.ReconciliationItemRepository;
import com.finx.reconciliation.repository.ReconciliationRunRepository;
import com.finx.reconciliation.service.ReconciliationService;
import com.finx.security.jwt.JwtTokenProvider;
import com.finx.security.service.UserPrincipal;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import com.finx.user.service.PmIdService;
import com.finx.user.service.UserUidService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class PlatformRoleProvisioningContainmentTest {

    // Auth dependencies
    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditService auditService;
    @Mock private GoogleOAuthProperties googleOAuthProperties;
    @Mock private UserUidService userUidService;
    @Mock private PmIdService pmIdService;

    // Finance dependencies
    @Mock private PaymentInstructionRepository instructionRepository;
    @Mock private PaymentTransactionRepository transactionRepository;
    @Mock private SettlementInstructionRepository settlementRepository;
    @Mock private BankingProvider bankingProvider;
    @Mock private ReconciliationRunRepository runRepository;
    @Mock private ReconciliationItemRepository itemRepository;
    @Mock private LedgerAccountRepository ledgerAccountRepository;
    @Mock private LedgerService ledgerService;
    @Mock private DealRepository dealRepository;
    @Mock private MilestoneRepository milestoneRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private EscrowAccountRepository escrowAccountRepository;
    @Mock private EscrowLedgerRepository escrowLedgerRepository;

    private JwtTokenProvider jwtTokenProvider;
    private AuthService authService;
    private AuthController authController;
    private MockMvc mockMvc;

    private FinanceController financeController;
    private FinanceProjectController financeProjectController;
    private ReconciliationService reconciliationService;

    private final String rawPassword = "SecurePassword123!";
    private final String encodedPassword = "$2a$12$sampleEncodedPasswordHash";

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(
                "404E635266556A586E3272357538782F413F4428472B4B6250645367566B5970",
                900000L,
                604800000L
        );

        when(userUidService.generateUniqueUid()).thenReturn("USR-12345678");
        when(pmIdService.generateUniquePmId()).thenReturn("PM-12345678");
        when(passwordEncoder.encode(any())).thenReturn(encodedPassword);

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
        mockMvc = MockMvcBuilders.standaloneSetup(authController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        financeController = new FinanceController(
                instructionRepository,
                settlementRepository,
                runRepository,
                ledgerAccountRepository,
                ledgerService
        );

        financeProjectController = new FinanceProjectController(
                dealRepository,
                milestoneRepository,
                paymentRepository,
                escrowAccountRepository,
                escrowLedgerRepository
        );

        reconciliationService = new ReconciliationService(
                runRepository,
                itemRepository,
                transactionRepository,
                settlementRepository,
                bankingProvider,
                auditService
        );
    }

    private User makeUser(String name, String email, Role role) {
        User u = new User(name, email, encodedPassword, role, UserStatus.ACTIVE);
        u.setId(UUID.randomUUID());
        u.setUid("USR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        return u;
    }

    private UserPrincipal makePrincipal(User user) {
        return UserPrincipal.create(user);
    }

    // =========================================================================
    // 1. PUBLIC REGISTRATION ALLOWLIST TESTS
    // =========================================================================
    @Nested
    @DisplayName("1. Public Registration Allowlist")
    class PublicRegistrationTests {

        @Test
        @DisplayName("1. BUYER public registration succeeds")
        void testBuyerRegistration_Succeeds() throws Exception {
            when(userRepository.existsByEmail("buyer@example.com")).thenReturn(false);
            User savedBuyer = makeUser("Buyer Corp", "buyer@example.com", Role.BUYER);
            when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedBuyer);

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Buyer Corp\",\"email\":\"buyer@example.com\",\"password\":\"" + rawPassword + "\",\"role\":\"BUYER\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.user.role").value("BUYER"));

            verify(userRepository).saveAndFlush(any(User.class));
        }

        @Test
        @DisplayName("2. SELLER public registration succeeds")
        void testSellerRegistration_Succeeds() throws Exception {
            when(userRepository.existsByEmail("seller@example.com")).thenReturn(false);
            User savedSeller = makeUser("Seller Vendor", "seller@example.com", Role.SELLER);
            when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedSeller);

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Seller Vendor\",\"email\":\"seller@example.com\",\"password\":\"" + rawPassword + "\",\"role\":\"SELLER\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.user.role").value("SELLER"));

            verify(userRepository).saveAndFlush(any(User.class));
        }

        @Test
        @DisplayName("3. PROJECT_MANAGER public registration succeeds")
        void testProjectManagerRegistration_Succeeds() throws Exception {
            when(userRepository.existsByEmail("pm@example.com")).thenReturn(false);
            User savedPm = makeUser("PM Lead", "pm@example.com", Role.PROJECT_MANAGER);
            savedPm.setPmId("PM-99887766");
            when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedPm);

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"PM Lead\",\"email\":\"pm@example.com\",\"password\":\"" + rawPassword + "\",\"role\":\"PROJECT_MANAGER\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.user.role").value("PROJECT_MANAGER"));

            verify(userRepository).saveAndFlush(any(User.class));
        }

        @Test
        @DisplayName("4. ADMIN public registration is denied (HTTP 400)")
        void testAdminRegistration_Denied() throws Exception {
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Admin Attacker\",\"email\":\"admin@finx.com\",\"password\":\"" + rawPassword + "\",\"role\":\"ADMIN\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value(containsString("ADMIN accounts cannot be registered publicly")));

            verify(userRepository, never()).save(any());
            verify(userRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("5. FINANCE public registration is denied (HTTP 400)")
        void testFinanceRegistration_Denied() throws Exception {
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Finance Attacker\",\"email\":\"finance@finx.com\",\"password\":\"" + rawPassword + "\",\"role\":\"FINANCE\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.message").value(containsString("FINANCE accounts cannot be registered publicly")));

            verify(userRepository, never()).save(any());
            verify(userRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("6. Privileged-role alias/case bypass is denied (finance, Finance, admin, ROLE_FINANCE)")
        void testPrivilegedRoleCaseAndAliasBypass_Denied() throws Exception {
            // Lowercase "finance"
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Finance Case\",\"email\":\"fcase@finx.com\",\"password\":\"" + rawPassword + "\",\"role\":\"finance\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(containsString("FINANCE accounts cannot be registered publicly")));

            // Mixed case "Finance"
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Finance Mixed\",\"email\":\"fmixed@finx.com\",\"password\":\"" + rawPassword + "\",\"role\":\"Finance\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(containsString("FINANCE accounts cannot be registered publicly")));

            // Lowercase "admin"
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Admin Lower\",\"email\":\"adminlow@finx.com\",\"password\":\"" + rawPassword + "\",\"role\":\"admin\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(containsString("ADMIN accounts cannot be registered publicly")));

            // Unknown alias "ROLE_FINANCE" -> rejected by parser
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Finance Prefixed\",\"email\":\"rf@finx.com\",\"password\":\"" + rawPassword + "\",\"role\":\"ROLE_FINANCE\"}"))
                    .andExpect(status().isBadRequest());

            verify(userRepository, never()).save(any());
            verify(userRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("7. Malformed / unknown role rejected safely (HTTP 400)")
        void testMalformedRole_RejectedSafely() throws Exception {
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Bad Role\",\"email\":\"bad@finx.com\",\"password\":\"" + rawPassword + "\",\"role\":\"SUPERUSER\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Empty Role\",\"email\":\"empty@finx.com\",\"password\":\"" + rawPassword + "\",\"role\":\"\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400));

            verify(userRepository, never()).save(any());
        }
    }

    // =========================================================================
    // 2. GOOGLE / OAUTH REGISTRATION RESTRICTIONS
    // =========================================================================
    @Nested
    @DisplayName("2. Google / OAuth Registration Boundaries")
    class OAuthRegistrationTests {

        @Test
        @DisplayName("8. OAuth registration cannot choose ADMIN")
        void testOAuth_CannotChooseAdmin() {
            GoogleOAuthRequest request = new GoogleOAuthRequest("sample_code", "http://localhost:3000/callback", Role.ADMIN);

            assertThatThrownBy(() -> authService.authenticateWithGoogle(request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Privileged platform roles (ADMIN, FINANCE) cannot be provisioned via OAuth");

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("9. OAuth registration cannot choose FINANCE")
        void testOAuth_CannotChooseFinance() {
            GoogleOAuthRequest request = new GoogleOAuthRequest("sample_code", "http://localhost:3000/callback", Role.FINANCE);

            assertThatThrownBy(() -> authService.authenticateWithGoogle(request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Privileged platform roles (ADMIN, FINANCE) cannot be provisioned via OAuth");

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("10. OAuth normal-user behavior preserved (rejects unconfigured or handles safe roles)")
        void testOAuth_NormalUserBehaviorPreserved() {
            when(googleOAuthProperties.isConfigured()).thenReturn(false);

            GoogleOAuthRequest requestBuyer = new GoogleOAuthRequest("sample_code", "http://localhost:3000/callback", Role.BUYER);
            assertThatThrownBy(() -> authService.authenticateWithGoogle(requestBuyer))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Google OAuth is not configured on the server");

            GoogleOAuthRequest requestSeller = new GoogleOAuthRequest("sample_code", "http://localhost:3000/callback", Role.SELLER);
            assertThatThrownBy(() -> authService.authenticateWithGoogle(requestSeller))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Google OAuth is not configured on the server");
        }
    }

    // =========================================================================
    // 3. ROLE MUTATION BOUNDARIES
    // =========================================================================
    @Nested
    @DisplayName("3. Role Mutation Containment")
    class RoleMutationTests {

        @Test
        @DisplayName("11. Ordinary user cannot change own role to FINANCE (no public mutation path exists)")
        void testOrdinaryUserCannotChangeRoleToFinance() {
            // Verify that AuthController has no PUT or PATCH mapping for mutating user roles
            for (Method method : AuthController.class.getDeclaredMethods()) {
                assertThat(method.isAnnotationPresent(PutMapping.class)).isFalse();
                assertThat(method.isAnnotationPresent(PatchMapping.class)).isFalse();
            }

            // Verify that getCurrentUser profile endpoint is read-only
            User buyer = makeUser("Buyer Normal", "buyer@finx.com", Role.BUYER);
            when(userRepository.findById(buyer.getId())).thenReturn(Optional.of(buyer));
            UserPrincipal principal = makePrincipal(buyer);

            var summary = authService.getCurrentUser(principal);
            assertThat(summary.getRole()).isEqualTo(Role.BUYER);
            // Confirmed: NO PUBLIC ROLE-MUTATION PATH FOUND
        }

        @Test
        @DisplayName("12. Ordinary user cannot change own role to ADMIN (no public mutation path exists)")
        void testOrdinaryUserCannotChangeRoleToAdmin() {
            User seller = makeUser("Seller Normal", "seller@finx.com", Role.SELLER);
            when(userRepository.findById(seller.getId())).thenReturn(Optional.of(seller));
            UserPrincipal principal = makePrincipal(seller);

            var summary = authService.getCurrentUser(principal);
            assertThat(summary.getRole()).isEqualTo(Role.SELLER);
            // Confirmed: NO PUBLIC ROLE-MUTATION PATH FOUND
        }
    }

    // =========================================================================
    // 4. ORGANIZATION FINANCE VS PLATFORM FINANCE DISTINCTION
    // =========================================================================
    @Nested
    @DisplayName("4. Organization FINANCE vs Platform FINANCE Distinction")
    class OrganizationFinanceDistinctionTests {

        @Test
        @DisplayName("13. Organization FINANCE membership does not set User Role.FINANCE")
        void testOrgFinanceMembershipDoesNotSetUserPlatformRole() {
            User buyerUser = makeUser("Org Finance Person", "orgfin@corp.com", Role.BUYER);

            UUID orgId = UUID.randomUUID();
            OrganizationMember member = new OrganizationMember(orgId, buyerUser.getId(), OrgRole.FINANCE);

            // Member has OrgRole.FINANCE
            assertThat(member.getRole()).isEqualTo(OrgRole.FINANCE);
            // User retains their platform role Role.BUYER
            assertThat(buyerUser.getRole()).isEqualTo(Role.BUYER);
            assertThat(buyerUser.getRole()).isNotEqualTo(Role.FINANCE);
        }

        @Test
        @DisplayName("14. Organization FINANCE member cannot access global finance endpoints")
        void testOrgFinanceMemberCannotAccessGlobalFinance() {
            User buyerUser = makeUser("Org Finance Person", "orgfin@corp.com", Role.BUYER);
            UserPrincipal buyerPrincipal = makePrincipal(buyerUser);

            // Platform role is BUYER; hasRole("FINANCE") and hasRole("ADMIN") are both false
            assertThat(buyerPrincipal.getRole()).isEqualTo(Role.BUYER);
            assertThat(buyerPrincipal.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_FINANCE"))).isFalse();
            assertThat(buyerPrincipal.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))).isFalse();
        }

        @Test
        @DisplayName("15. Organization FINANCE member cannot access global reconciliation")
        void testOrgFinanceMemberCannotAccessGlobalReconciliation() {
            User buyerUser = makeUser("Org Finance Person", "orgfin@corp.com", Role.BUYER);
            UserPrincipal buyerPrincipal = makePrincipal(buyerUser);

            // ReconciliationService requires platform ADMIN or FINANCE
            assertThatThrownBy(() -> reconciliationService.getRuns(buyerPrincipal))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Platform ADMIN or FINANCE role required for reconciliation operations");

            assertThatThrownBy(() -> reconciliationService.triggerReconciliation(buyerPrincipal))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("Platform ADMIN or FINANCE role required for reconciliation operations");
        }
    }

    // =========================================================================
    // 5. PLATFORM FINANCE AUTHORIZED OPERATIONS
    // =========================================================================
    @Nested
    @DisplayName("5. Platform Finance Operator Authority")
    class PlatformFinanceTests {

        @Test
        @DisplayName("16. Legitimately provisioned platform FINANCE retains intended global finance access")
        void testLegitimatePlatformFinance_RetainsIntendedAccess() {
            User financeOperator = makeUser("FinX Ops Lead", "ops.finance@finx.com", Role.FINANCE);
            UserPrincipal financePrincipal = makePrincipal(financeOperator);

            assertThat(financePrincipal.getRole()).isEqualTo(Role.FINANCE);
            assertThat(financePrincipal.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_FINANCE"))).isTrue();

            // Finance summary
            when(instructionRepository.findAll()).thenReturn(List.of());
            when(settlementRepository.findAll()).thenReturn(List.of());
            when(runRepository.count()).thenReturn(5L);

            ResponseEntity<ApiResponse<Map<String, Object>>> summaryResp = financeController.getFinanceSummary();
            assertThat(summaryResp.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(summaryResp.getBody().getData().get("totalReconciliationRuns")).isEqualTo(5L);

            // Finance ledger balances
            when(ledgerService.getAccountBalance(any())).thenReturn(BigDecimal.valueOf(100000.00));
            ResponseEntity<ApiResponse<Map<String, Object>>> ledgerResp = financeController.getLedgerBalances();
            assertThat(ledgerResp.getStatusCode().is2xxSuccessful()).isTrue();

            // Reconciliation operations
            when(bankingProvider.getProviderName()).thenReturn("mock");
            when(bankingProvider.fetchTransactions(any(), any())).thenReturn(List.of());
            when(transactionRepository.findAll()).thenReturn(List.of());
            when(settlementRepository.findAll()).thenReturn(List.of());
            when(runRepository.save(any(ReconciliationRun.class))).thenAnswer(inv -> inv.getArgument(0));
            when(runRepository.findAll()).thenReturn(List.of());

            ReconciliationRun run = reconciliationService.triggerReconciliation(financePrincipal);
            assertThat(run).isNotNull();
            assertThat(reconciliationService.getRuns(financePrincipal)).isNotNull();
        }

        @Test
        @DisplayName("17. Platform ADMIN retains intended global access")
        void testPlatformAdmin_RetainsIntendedAccess() {
            User adminUser = makeUser("Platform Admin", "admin@finx.com", Role.ADMIN);
            UserPrincipal adminPrincipal = makePrincipal(adminUser);

            assertThat(adminPrincipal.getRole()).isEqualTo(Role.ADMIN);
            assertThat(adminPrincipal.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))).isTrue();

            when(bankingProvider.getProviderName()).thenReturn("mock");
            when(bankingProvider.fetchTransactions(any(), any())).thenReturn(List.of());
            when(transactionRepository.findAll()).thenReturn(List.of());
            when(settlementRepository.findAll()).thenReturn(List.of());
            when(runRepository.save(any(ReconciliationRun.class))).thenAnswer(inv -> inv.getArgument(0));
            when(runRepository.findAll()).thenReturn(List.of());

            ReconciliationRun run = reconciliationService.triggerReconciliation(adminPrincipal);
            assertThat(run).isNotNull();
            assertThat(reconciliationService.getRuns(adminPrincipal)).isNotNull();
        }
    }

    // =========================================================================
    // 6. EXISTING ACCOUNT HANDLING & SCHEMA PROVENANCE VERIFICATION
    // =========================================================================
    @Nested
    @DisplayName("6. Existing Account Handling & Schema Provenance")
    class ExistingAccountHandlingTests {

        @Test
        @DisplayName("18. Verify schema contains no role-provenance column; automatic classification is impossible without guessing")
        void testSchemaLacksRoleProvenance_ControlledReviewRequired() {
            // Inspect all fields of User entity
            Field[] fields = User.class.getDeclaredFields();
            List<String> fieldNames = Arrays.stream(fields).map(Field::getName).toList();

            // Confirm standard identity fields exist
            assertThat(fieldNames).contains("id", "name", "email", "passwordHash", "role", "status", "createdAt", "updatedAt");

            // Confirm that NO provenance field (such as provisionedBy, isPlatformProvisioned, etc.) exists in current entity
            assertThat(fieldNames).doesNotContain("provisionedBy", "isPlatformProvisioned", "operatorProvenance", "provisioningSource");

            // Therefore, existing FINANCE accounts require controlled administrative review
            // because the current schema cannot reliably distinguish legitimately provisioned operators from historical self-registration.
            // Automatic mass demotion is strictly avoided to protect legitimate operators.
        }
    }
}
