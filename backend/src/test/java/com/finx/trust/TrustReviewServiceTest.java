package com.finx.trust;

import com.finx.audit.service.AuditService;
import com.finx.exception.BadRequestException;
import com.finx.exception.ResourceNotFoundException;
import com.finx.trust.entity.TrustManualReview;
import com.finx.trust.entity.TrustOrganization;
import com.finx.trust.repository.TrustManualReviewRepository;
import com.finx.trust.repository.TrustOrganizationRepository;
import com.finx.trust.service.TrustReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrustReviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");

    @Mock
    private TrustManualReviewRepository reviews;
    @Mock
    private TrustOrganizationRepository organizations;
    @Mock
    private AuditService audit;

    private TrustReviewService service;

    @BeforeEach
    void setUp() {
        service = new TrustReviewService(reviews, organizations, audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createPersistsOpenReviewWithClockTimestampAndAudit() {
        UUID organizationId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        when(organizations.lock(organizationId)).thenReturn(Optional.of(mock(TrustOrganization.class)));

        TrustManualReview review = service.create(organizationId, adminId, "Unusual activity");

        assertThat(review.id).isNotNull();
        assertThat(review.organizationId).isEqualTo(organizationId);
        assertThat(review.adminId).isEqualTo(adminId);
        assertThat(review.reason).isEqualTo("Unusual activity");
        assertThat(review.status).isEqualTo("OPEN");
        assertThat(review.createdAt).isEqualTo(NOW);
        verify(reviews).save(review);
        verify(audit).logEvent(adminId, "TRUST_REVIEW_CREATED", "TRUST_REVIEW",
                review.id.toString(), "Unusual activity");
    }

    @Test
    void createRejectsUnknownOrganizationWithoutSavingReview() {
        UUID organizationId = UUID.randomUUID();
        when(organizations.lock(organizationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(organizationId, UUID.randomUUID(), "Review"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Organization");

        verifyNoInteractions(reviews, audit);
    }

    @Test
    void resolveSetsResolutionFieldsUsingClockAndWritesAudit() {
        UUID reviewId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        TrustManualReview review = new TrustManualReview();
        review.id = reviewId;
        review.organizationId = organizationId;
        review.status = "OPEN";
        when(reviews.lock(reviewId)).thenReturn(Optional.of(review));
        when(organizations.lock(organizationId)).thenReturn(Optional.of(mock(TrustOrganization.class)));

        TrustManualReview resolved = service.resolve(reviewId, adminId, "Evidence reviewed");

        assertThat(resolved.status).isEqualTo("RESOLVED");
        assertThat(resolved.resolvedBy).isEqualTo(adminId);
        assertThat(resolved.resolution).isEqualTo("Evidence reviewed");
        assertThat(resolved.resolvedAt).isEqualTo(NOW);
        verify(audit).logEvent(adminId, "TRUST_REVIEW_RESOLVED", "TRUST_REVIEW",
                reviewId.toString(), "Evidence reviewed");
        verify(reviews, never()).save(any(TrustManualReview.class));
    }

    @Test
    void resolveRejectsReviewThatIsAlreadyClosed() {
        UUID reviewId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        TrustManualReview review = new TrustManualReview();
        review.id = reviewId;
        review.organizationId = organizationId;
        review.status = "RESOLVED";
        when(reviews.lock(reviewId)).thenReturn(Optional.of(review));
        when(organizations.lock(organizationId)).thenReturn(Optional.of(mock(TrustOrganization.class)));

        assertThatThrownBy(() -> service.resolve(reviewId, UUID.randomUUID(), "Second resolution"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("already resolved");

        verifyNoInteractions(audit);
    }
}