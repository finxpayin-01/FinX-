package com.finx.deal;

import com.finx.audit.service.AuditService;
import com.finx.common.enums.Role;
import com.finx.common.enums.UserStatus;
import com.finx.deal.dto.request.CreateDealRequest;
import com.finx.deal.dto.request.RejectDealRequest;
import com.finx.deal.dto.response.DealResponse;
import com.finx.deal.entity.Deal;
import com.finx.deal.entity.DealStatus;
import com.finx.deal.repository.DealRepository;
import com.finx.deal.service.DealService;
import com.finx.exception.BadRequestException;
import com.finx.exception.ResourceNotFoundException;
import com.finx.exception.UnauthorizedException;
import com.finx.notification.service.NotificationService;
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
import java.time.Instant;
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
    private NotificationService notificationService;

    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    private DealService dealService;

    private User buyer;
    private User seller;
    private UserPrincipal buyerPrincipal;
    private UserPrincipal sellerPrincipal;

    @BeforeEach
    void setUp() {
        dealService = new DealService(dealRepository, userRepository, auditService, notificationService, eventPublisher);

        buyer = new User("Buyer One", "buyer@finx.com", "hash", Role.BUYER, UserStatus.ACTIVE);
        buyer.setId(UUID.randomUUID());

        seller = new User("Seller One", "seller@finx.com", "hash", Role.SELLER, UserStatus.ACTIVE);
        seller.setId(UUID.randomUUID());

        buyerPrincipal = UserPrincipal.create(buyer);
        sellerPrincipal = UserPrincipal.create(seller);
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
        assertThat(response.getStatus()).isEqualTo(DealStatus.PENDING_ACCEPTANCE);

        verify(auditService, times(1)).logEvent(
                eq(buyer.getId()),
                eq("DEAL_CREATED"),
                eq("DEAL"),
                any(),
                any()
        );
        verify(notificationService, times(1)).sendNotification(
                eq(seller.getId()),
                eq("New Project Invitation"),
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

    @Test
    @DisplayName("Assigned vendor successfully accepts a pending project")
    void acceptDeal_Success() {
        Deal deal = new Deal("Web Development", "Desc", buyer.getId(), seller.getId(), new BigDecimal("30000.00"), "INR", DealStatus.PENDING_ACCEPTANCE);
        deal.setId(UUID.randomUUID());

        when(dealRepository.findById(deal.getId())).thenReturn(Optional.of(deal));
        when(dealRepository.saveAndFlush(any(Deal.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findById(seller.getId())).thenReturn(Optional.of(seller));

        DealResponse response = dealService.acceptDeal(deal.getId(), sellerPrincipal);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(DealStatus.ACTIVE);
        assertThat(response.getAcceptedBy()).isEqualTo(seller.getId());
        assertThat(response.getAcceptedAt()).isNotNull();

        verify(dealRepository, times(1)).saveAndFlush(argThat(d ->
                d.getStatus() == DealStatus.ACTIVE &&
                d.getAcceptedBy().equals(seller.getId()) &&
                d.getAcceptedAt() != null
        ));

        verify(auditService, times(1)).logEvent(
                eq(seller.getId()),
                eq("PROJECT_ACCEPTED"),
                eq("DEAL"),
                eq(deal.getId().toString()),
                contains("Web Development")
        );

        verify(notificationService, times(1)).sendNotification(
                eq(buyer.getId()),
                eq("Project Accepted"),
                contains("accepted Web Development"),
                contains(deal.getId().toString())
        );
    }

    @Test
    @DisplayName("Buyer attempting to accept own project is denied with UnauthorizedException")
    void acceptDeal_BuyerDenied_ThrowsUnauthorizedException() {
        Deal deal = new Deal("Web Development", "Desc", buyer.getId(), seller.getId(), new BigDecimal("30000.00"), "INR", DealStatus.PENDING_ACCEPTANCE);
        deal.setId(UUID.randomUUID());

        when(dealRepository.findById(deal.getId())).thenReturn(Optional.of(deal));

        assertThatThrownBy(() -> dealService.acceptDeal(deal.getId(), buyerPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Buyer cannot accept their own project invitation");

        verify(dealRepository, never()).saveAndFlush(any());
        verify(auditService, never()).logEvent(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Different vendor attempting to accept project is denied with UnauthorizedException")
    void acceptDeal_DifferentVendorDenied_ThrowsUnauthorizedException() {
        User otherSeller = new User("Other Seller", "other.seller@finx.com", "hash", Role.SELLER, UserStatus.ACTIVE);
        otherSeller.setId(UUID.randomUUID());
        UserPrincipal otherSellerPrincipal = UserPrincipal.create(otherSeller);

        Deal deal = new Deal("Web Development", "Desc", buyer.getId(), seller.getId(), new BigDecimal("30000.00"), "INR", DealStatus.PENDING_ACCEPTANCE);
        deal.setId(UUID.randomUUID());

        when(dealRepository.findById(deal.getId())).thenReturn(Optional.of(deal));

        assertThatThrownBy(() -> dealService.acceptDeal(deal.getId(), otherSellerPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Only the assigned vendor can accept this deal");

        verify(dealRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Unauthenticated request to accept deal is denied with UnauthorizedException")
    void acceptDeal_Unauthenticated_ThrowsUnauthorizedException() {
        Deal deal = new Deal("Web Development", "Desc", buyer.getId(), seller.getId(), new BigDecimal("30000.00"), "INR", DealStatus.PENDING_ACCEPTANCE);
        deal.setId(UUID.randomUUID());

        when(dealRepository.findById(deal.getId())).thenReturn(Optional.of(deal));

        assertThatThrownBy(() -> dealService.acceptDeal(deal.getId(), null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Authentication required");
    }

    @Test
    @DisplayName("Accepting an already ACTIVE deal throws BadRequestException")
    void acceptDeal_AlreadyActive_ThrowsBadRequestException() {
        Deal deal = new Deal("Web Development", "Desc", buyer.getId(), seller.getId(), new BigDecimal("30000.00"), "INR", DealStatus.ACTIVE);
        deal.setId(UUID.randomUUID());

        when(dealRepository.findById(deal.getId())).thenReturn(Optional.of(deal));

        assertThatThrownBy(() -> dealService.acceptDeal(deal.getId(), sellerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Deal is already accepted and active");
    }

    @Test
    @DisplayName("Accepting a REJECTED or COMPLETED deal throws BadRequestException")
    void acceptDeal_InvalidStatus_ThrowsBadRequestException() {
        Deal deal = new Deal("Web Development", "Desc", buyer.getId(), seller.getId(), new BigDecimal("30000.00"), "INR", DealStatus.REJECTED);
        deal.setId(UUID.randomUUID());

        when(dealRepository.findById(deal.getId())).thenReturn(Optional.of(deal));

        assertThatThrownBy(() -> dealService.acceptDeal(deal.getId(), sellerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Deal cannot be accepted from status: REJECTED");
    }

    @Test
    @DisplayName("Assigned vendor successfully rejects a project with reason")
    void rejectDeal_Success() {
        Deal deal = new Deal("Mobile App", "Desc", buyer.getId(), seller.getId(), new BigDecimal("50000.00"), "INR", DealStatus.PENDING_ACCEPTANCE);
        deal.setId(UUID.randomUUID());

        when(dealRepository.findById(deal.getId())).thenReturn(Optional.of(deal));
        when(dealRepository.saveAndFlush(any(Deal.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findById(seller.getId())).thenReturn(Optional.of(seller));

        RejectDealRequest rejectRequest = new RejectDealRequest("Schedule conflict with other commitments");
        DealResponse response = dealService.rejectDeal(deal.getId(), rejectRequest, sellerPrincipal);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(DealStatus.REJECTED);
        assertThat(response.getRejectedBy()).isEqualTo(seller.getId());
        assertThat(response.getRejectedAt()).isNotNull();
        assertThat(response.getRejectionReason()).isEqualTo("Schedule conflict with other commitments");

        verify(dealRepository, times(1)).saveAndFlush(argThat(d ->
                d.getStatus() == DealStatus.REJECTED &&
                d.getRejectedBy().equals(seller.getId()) &&
                "Schedule conflict with other commitments".equals(d.getRejectionReason())
        ));

        verify(auditService, times(1)).logEvent(
                eq(seller.getId()),
                eq("PROJECT_REJECTED"),
                eq("DEAL"),
                eq(deal.getId().toString()),
                contains("Schedule conflict with other commitments")
        );

        verify(notificationService, times(1)).sendNotification(
                eq(buyer.getId()),
                eq("Project Declined"),
                contains("rejected Mobile App"),
                contains(deal.getId().toString())
        );
    }

    @Test
    @DisplayName("Buyer attempting to reject own project is denied with UnauthorizedException")
    void rejectDeal_BuyerDenied_ThrowsUnauthorizedException() {
        Deal deal = new Deal("Mobile App", "Desc", buyer.getId(), seller.getId(), new BigDecimal("50000.00"), "INR", DealStatus.PENDING_ACCEPTANCE);
        deal.setId(UUID.randomUUID());

        when(dealRepository.findById(deal.getId())).thenReturn(Optional.of(deal));

        assertThatThrownBy(() -> dealService.rejectDeal(deal.getId(), new RejectDealRequest("No"), buyerPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Buyer cannot reject their own project invitation");
    }

    @Test
    @DisplayName("Different vendor attempting to reject project is denied with UnauthorizedException")
    void rejectDeal_DifferentVendorDenied_ThrowsUnauthorizedException() {
        User otherSeller = new User("Other Seller", "other.seller@finx.com", "hash", Role.SELLER, UserStatus.ACTIVE);
        otherSeller.setId(UUID.randomUUID());
        UserPrincipal otherSellerPrincipal = UserPrincipal.create(otherSeller);

        Deal deal = new Deal("Mobile App", "Desc", buyer.getId(), seller.getId(), new BigDecimal("50000.00"), "INR", DealStatus.PENDING_ACCEPTANCE);
        deal.setId(UUID.randomUUID());

        when(dealRepository.findById(deal.getId())).thenReturn(Optional.of(deal));

        assertThatThrownBy(() -> dealService.rejectDeal(deal.getId(), new RejectDealRequest("No"), otherSellerPrincipal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Only the assigned vendor can reject this deal");
    }

    @Test
    @DisplayName("Rejecting an already REJECTED deal throws BadRequestException")
    void rejectDeal_AlreadyRejected_ThrowsBadRequestException() {
        Deal deal = new Deal("Mobile App", "Desc", buyer.getId(), seller.getId(), new BigDecimal("50000.00"), "INR", DealStatus.REJECTED);
        deal.setId(UUID.randomUUID());

        when(dealRepository.findById(deal.getId())).thenReturn(Optional.of(deal));

        assertThatThrownBy(() -> dealService.rejectDeal(deal.getId(), new RejectDealRequest("No"), sellerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Deal is already rejected");
    }

    @Test
    @DisplayName("Rejecting an ACTIVE deal throws BadRequestException")
    void rejectDeal_ActiveDeal_ThrowsBadRequestException() {
        Deal deal = new Deal("Mobile App", "Desc", buyer.getId(), seller.getId(), new BigDecimal("50000.00"), "INR", DealStatus.ACTIVE);
        deal.setId(UUID.randomUUID());

        when(dealRepository.findById(deal.getId())).thenReturn(Optional.of(deal));

        assertThatThrownBy(() -> dealService.rejectDeal(deal.getId(), new RejectDealRequest("No"), sellerPrincipal))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Deal cannot be rejected from status: ACTIVE");
    }
}
