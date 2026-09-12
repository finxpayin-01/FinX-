package com.finx.deal;

import com.finx.audit.service.AuditService;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.dto.request.CreateDealRequest;
import com.finx.deal.dto.response.DealResponse;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.deal.service.DealService;
import com.finx.exception.BadRequestException;
import com.finx.exception.ResourceNotFoundException;
import com.finx.security.service.UserPrincipal;
import com.finx.user.entity.User;
import com.finx.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DealServiceTest {

    @Mock
    private DealRepository dealRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    private DealService dealService;

    private User buyer;
    private User seller;
    private UserPrincipal buyerPrincipal;

    @BeforeEach
    void setUp() {
        dealService = new DealService(dealRepository, userRepository, auditService, eventPublisher);

        buyer = new User("Buyer One", "buyer@finx.com", "hash", Role.BUYER, UserStatus.ACTIVE);
        buyer.setId(UUID.randomUUID());

        seller = new User("Seller One", "seller@finx.com", "hash", Role.SELLER, UserStatus.ACTIVE);
        seller.setId(UUID.randomUUID());

        buyerPrincipal = UserPrincipal.create(buyer);
    }

    @Test
    @DisplayName("Successfully create a deal with valid buyer and seller")
    void createDeal_Success() {
        CreateDealRequest request = new CreateDealRequest();
        request.setSellerId(seller.getId());
        request.setTitle("Cross-Border Machinery Purchase");
        request.setDescription("Payment held in escrow until goods inspected");
        request.setTotalAmount(new BigDecimal("50000.00"));
        request.setCurrency("USD");

        when(userRepository.findById(buyer.getId())).thenReturn(Optional.of(buyer));
        when(userRepository.findById(seller.getId())).thenReturn(Optional.of(seller));
        when(dealRepository.saveAndFlush(any(Deal.class))).thenAnswer(invocation -> {
            Deal d = invocation.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });

        DealResponse response = dealService.createDeal(request, buyerPrincipal);

        assertThat(response).isNotNull();
        assertThat(response.getTitle()).isEqualTo("Cross-Border Machinery Purchase");
        assertThat(response.getBuyerId()).isEqualTo(buyer.getId());
        assertThat(response.getSellerId()).isEqualTo(seller.getId());
        assertThat(response.getTotalAmount()).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(response.getStatus()).isEqualTo(DealStatus.DRAFT);

        verify(auditService, times(1)).logEvent(
                eq(buyer.getId()),
                eq("DEAL_CREATED"),
                eq("DEAL"),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("Fail to create deal if buyer and seller are the same user")
    void createDeal_SameBuyerAndSeller_ThrowsException() {
        CreateDealRequest request = new CreateDealRequest();
        request.setSellerId(buyer.getId());
        request.setTitle("Self Deal");
        request.setTotalAmount(new BigDecimal("1000.00"));
        request.setCurrency("USD");

        assertThatThrownBy(() -> dealService.createDeal(request, buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Buyer and Seller cannot be the same account");
    }

    @Test
    @DisplayName("Fail to create deal if specified seller does not have SELLER role")
    void createDeal_InvalidSellerRole_ThrowsException() {
        User otherBuyer = new User("Other Buyer", "other@finx.com", "hash", Role.BUYER, UserStatus.ACTIVE);
        otherBuyer.setId(UUID.randomUUID());

        CreateDealRequest request = new CreateDealRequest();
        request.setSellerId(otherBuyer.getId());
        request.setTitle("Invalid Seller Deal");
        request.setTotalAmount(new BigDecimal("2000.00"));
        request.setCurrency("USD");

        when(userRepository.findById(buyer.getId())).thenReturn(Optional.of(buyer));
        when(userRepository.findById(otherBuyer.getId())).thenReturn(Optional.of(otherBuyer));

        assertThatThrownBy(() -> dealService.createDeal(request, buyerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Specified seller must have the SELLER role");
    }

    @Test
    @DisplayName("Get deal by id returns deal when present")
    void getDealById_Found() {
        Deal deal = new Deal("Test Deal", "Desc", buyer.getId(), seller.getId(), new BigDecimal("10000.00"), "USD", DealStatus.DRAFT);

        when(dealRepository.findById(deal.getId())).thenReturn(Optional.of(deal));

        DealResponse response = dealService.getDealById(deal.getId());
        assertThat(response).isNotNull();
        assertThat(response.getTitle()).isEqualTo("Test Deal");
    }

    @Test
    @DisplayName("Get deal by id throws ResourceNotFoundException when missing")
    void getDealById_NotFound_ThrowsException() {
        UUID randomId = UUID.randomUUID();
        when(dealRepository.findById(randomId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dealService.getDealById(randomId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("List deals for buyer returns buyer deals")
    void getDealsForBuyer_ReturnsDeals() {
        Deal deal = new Deal("Buyer Deal", "Desc", buyer.getId(), seller.getId(), new BigDecimal("5000.00"), "USD", DealStatus.ACTIVE);
        when(dealRepository.findByBuyerId(buyer.getId())).thenReturn(List.of(deal));

        List<DealResponse> deals = dealService.getDealsForBuyer(buyer.getId());
        assertThat(deals).hasSize(1);
        assertThat(deals.get(0).getTitle()).isEqualTo("Buyer Deal");
    }

    @Test
    @DisplayName("Successfully create deal when seller is referenced by UID (USR-XXXX)")
    void createDeal_SuccessWithVendorUid() {
        seller.setUid("USR-ABCD12");
        CreateDealRequest request = new CreateDealRequest();
        request.setSellerId("USR-ABCD12");
        request.setTitle("Vendor UID Deal");
        request.setTotalAmount(new BigDecimal("15000.00"));
        request.setCurrency("INR");

        when(userRepository.findByUidIgnoreCase("USR-ABCD12")).thenReturn(Optional.of(seller));
        when(userRepository.findById(buyer.getId())).thenReturn(Optional.of(buyer));
        when(dealRepository.saveAndFlush(any(Deal.class))).thenAnswer(invocation -> {
            Deal d = invocation.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });

        DealResponse response = dealService.createDeal(request, buyerPrincipal);
        assertThat(response.getSellerId()).isEqualTo(seller.getId());
    }

    @Test
    @DisplayName("Reject deal creation when vendor UID is not found")
    void createDeal_RejectsInvalidVendorUid() {
        CreateDealRequest request = new CreateDealRequest();
        request.setSellerId("USR-NONEXISTENT");
        request.setTitle("Invalid Vendor Deal");
        request.setTotalAmount(new BigDecimal("1000.00"));
        request.setCurrency("INR");

        when(userRepository.findByUidIgnoreCase("USR-NONEXISTENT")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dealService.createDeal(request, buyerPrincipal))
                .isInstanceOf(com.finx.exception.BadRequestException.class)
                .hasMessageContaining("Vendor UID not found");
    }

    @Test
    @DisplayName("Reject deal creation when referenced user does not have SELLER role")
    void createDeal_RejectsNonSellerRole() {
        User nonSeller = new User("PM User", "pm@finx.com", "hash", Role.PROJECT_MANAGER, UserStatus.ACTIVE);
        nonSeller.setId(UUID.randomUUID());
        nonSeller.setUid("USR-PMUSER12");

        CreateDealRequest request = new CreateDealRequest();
        request.setSellerId("USR-PMUSER12");
        request.setTitle("Wrong Role Deal");
        request.setTotalAmount(new BigDecimal("1000.00"));
        request.setCurrency("INR");

        when(userRepository.findByUidIgnoreCase("USR-PMUSER12")).thenReturn(Optional.of(nonSeller));
        when(userRepository.findById(buyer.getId())).thenReturn(Optional.of(buyer));

        assertThatThrownBy(() -> dealService.createDeal(request, buyerPrincipal))
                .isInstanceOf(com.finx.exception.BadRequestException.class)
                .hasMessageContaining("must have the SELLER role");
    }
}
